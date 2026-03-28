# 📊 Load Testing, Performance & Debugging

> Real results from Locust load testing + the file upload bug story — great for showing depth of engineering thinking.

---

## 1. Load Testing with Locust

### What Is Locust?

**Locust** is an open-source Python-based load testing tool. Unlike JMeter (XML-based), Locust uses Python code to define user behavior — making tests readable and maintainable.

```python
# Example Locust test for StreamLite HLS segments
from locust import HttpUser, task, between

class StreamLiteUser(HttpUser):
    wait_time = between(0.5, 2)  # Simulate realistic viewer behavior
    
    @task
    def get_playlist(self):
        self.client.get("/stream/video-id/playlist.m3u8")
    
    @task(5)  # 5x more likely than playlist
    def get_segment(self):
        self.client.get("/stream/video-id/playlist3.ts")
```

Run it: `locust -f locustfile.py --host http://localhost:8000`

---

## 2. Load Test Results — What the Numbers Mean

### Test Configuration
- Tool: Locust
- Target: Nginx (serving HLS segments directly — bypassing Spring for max throughput test)
- Concurrent users: Ramped up gradually

### Results Observed

| Metric | Playlist Endpoint | Segment Endpoint |
|---|---|---|
| Average response time | **8ms** | **45ms** |
| Errors | **0** | **0** |
| Peak RPS | **49 RPS** (playlist) | **488 RPS** (segments) |
| Segment file size | — | ~1.317 MB |

### The Bandwidth Calculation (The Key Finding)

```
Peak RPS = 488 requests/second
Segment size = 1,317,162 bytes (~1.317 MB)

Throughput = 488 × 1.317 MB = 642 MB/second

Convert to network speed:
642 MB/s × 8 bits/byte = 5,136 Mbps ≈ 5.14 Gbps
```

**Conclusion:** The server saturated its **5 Gbps Network Interface Card** — not the CPU, not the application, not Redis or Spring. This means the software architecture is correct; the hardware is the bottleneck. To serve more users, upgrade to a 10 Gbps NIC or add more servers.

---

## 3. What "488 RPS at 0% Error Rate" Means for an Interview

**Per viewer behavior:**
- A viewer watching 720p video consumes ~3-5 Mbps of bandwidth
- Each 10-second, 1.317MB segment takes: 1.317 × 8 / 3 = ~3.5 seconds to download at 3 Mbps
- At 488 RPS with 1.317 MB each → serving simultaneous segments to ~488 viewers

**Concurrent viewers calculation:**
- Each viewer requests 1 segment every 10 seconds
- At 488 requests/sec, if each viewer requests once every 10 seconds:
- `488 RPS × 10 seconds = ~4,880 concurrent viewers`

This is the capacity of a single server setup.

---

## 4. Nginx as a Reverse Proxy / CDN Layer

In the production-like setup, Nginx was placed in front of Spring Boot:

```
Browser → Nginx (port 80/443) → Spring Boot (port 8000)
```

**Why Nginx for HLS?**
1. **Static file serving:** Nginx serves `.m3u8` and `.ts` files directly from disk — much faster than routing through Spring MVC
2. **Caching:** Nginx can cache HLS segments in memory with `proxy_cache`
3. **Connection handling:** Nginx is event-driven (uses Linux epoll) — handles 10,000+ simultaneous connections with minimal CPU
4. **SSL termination:** Handles HTTPS, Spring only sees plain HTTP

**Sample Nginx config for HLS:**
```nginx
server {
    listen 80;
    
    # Serve HLS files directly
    location /hls/ {
        alias /path/to/StreamLite/videos/output/;
        add_header Content-Type application/x-mpegURL;
        add_header Cache-Control "no-cache";
    }
    
    # Proxy API requests to Spring Boot
    location / {
        proxy_pass http://localhost:8000;
        proxy_set_header Host $host;
    }
}
```

---

## 5. The Streaming Bug — File Upload Fix (Real Debugging Experience)

*This is documented in `docs/streamingbug.md`. Here's the layered analysis:*

### Phase 1: FileNotFoundException

```
java.io.FileNotFoundException: 
    ...\videos\input\videoplayback.mp4 (The system cannot find the path specified)
```

**Root cause:** `file.transferTo(dest)` relies on Tomcat's internal temp directory for an intermediate copy. The temp directory path doesn't exist because Tomcat is running in a non-standard mode inside Spring Boot.

**Fix:** Redirect temp storage:
```properties
spring.servlet.multipart.location=${user.dir}/videos/tmp
```

### Phase 2: FileUploadException

```
org.apache.tomcat.util.http.fileupload.FileUploadException: Cannot write uploaded file to disk!
```

**Root cause:** Even after redirecting, `transferTo()` has a race condition with Tomcat's internal file management.

**Fix:** Stop using `transferTo()`. Use `Files.copy()` with direct `InputStream`:

```java
// BEFORE (unreliable):
file.transferTo(dest);

// AFTER (reliable):
try (var inputStream = file.getInputStream()) {
    Files.copy(inputStream, dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
}
```

### Phase 3: File Locking (Windows)

```
The process cannot access the file because it is being used by another process
```

**Root cause:** Windows NTFS held an OS-level file lock briefly after `Files.copy()` closed. FFmpeg tried to open the file during this window.

**Fix:** 300ms sleep to let OS release the handle:
```java
Thread.sleep(300);
// FFmpeg invoked here
```

### Lessons Learned (Say These in Interview)

1. **Know your platform:** Windows file locking behaves differently than Linux/macOS. `Thread.sleep()` is a pragmatic workaround; proper fix uses `FileLock` API or a file watcher.
2. **Use the right primitive:** `Files.copy(InputStream, Path)` > `MultipartFile.transferTo()` for custom paths.
3. **Explicit directory paths:** Never rely on Tomcat's temp directory path — always set `multipart.location` explicitly.
4. **`processBuilder.inheritIO()`** was critical for debugging — FFmpeg's output (showing exactly which file it couldn't open) made the issue obvious.

---

## 6. Bean Validation — `@Valid` and `@NotBlank`

StreamLite uses Jakarta Bean Validation (formerly Hibernate Validator):

```java
// Video entity:
@NotBlank(message = "Title is required")
@Size(max = 255, message = "Title must not exceed 255 characters")
private String title;

// UserForm:
@Email(message = "Invalid email format")
@NotBlank(message = "Email is required")
private String email;
```

**How validation works:**

```java
// In VideoController:
@PostMapping
public ResponseEntity<?> createVideo(@Valid @RequestBody VideoRequest request) {
    // @Valid triggers validation on VideoRequest fields
    // If any constraint fails → MethodArgumentNotValidException is thrown
    // Spring converts this to HTTP 400 Bad Request automatically
}
```

**In form login (PageController):**
```java
@PostMapping("/do-register")
public String processRegister(
    @Valid @ModelAttribute UserForm userForm, 
    BindingResult rBindingResult,  // Binding result captures errors
    HttpSession session
) {
    if (rBindingResult.hasErrors()) {
        return "register";  // Re-show form with error messages
    }
    // ... proceed with registration
}
```

---

## 7. Custom Exception Handling

```java
// ResourceNotFoundException.java
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
```

Used in service layer:
```java
User user = userRepo.findById(id)
    .orElseThrow(() -> new ResourceNotFoundException("User not found"));
```

Used in controller:
```java
catch (ResourceNotFoundException e) {
    return ResponseEntity.notFound().build();  // HTTP 404
}
```

A production improvement would be a `@ControllerAdvice` class with `@ExceptionHandler` to centralize exception-to-HTTP-status mapping:

```java
@ControllerAdvice
public class GlobalExceptionHandler {
    
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException e) {
        return ResponseEntity.status(404)
            .body(new ErrorResponse(e.getMessage(), 404));
    }
    
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors()
            .stream().map(FieldError::getDefaultMessage)
            .collect(Collectors.joining(", "));
        return ResponseEntity.status(400).body(new ErrorResponse(message, 400));
    }
}
```

---

## 8. Testing — What Was Done in StreamLite

### `src/test/java/` — Spring Boot Test Setup

Spring Boot auto-configures a test environment:

```java
@SpringBootTest  // Loads full application context
class StreamLiteTests {
    
    @Autowired
    private UserService userService;
    
    @Test
    void contextLoads() {
        // Verifies Spring context starts without errors
    }
    
    @Test
    void testSaveUser() {
        User user = new User();
        user.setEmail("test@example.com");
        user.setPassword("password");
        User saved = userService.saveUser(user);
        assertNotNull(saved.getUserId());
        // Password should be BCrypt hashed
        assertNotEquals("password", saved.getPassword());
        assertTrue(saved.getPassword().startsWith("$2a$"));
    }
}
```

### Integration Testing with `@WebMvcTest`

```java
@WebMvcTest(StreamingController.class)  // Only loads web layer, not full context
class StreamingControllerTest {
    
    @Autowired
    private MockMvc mockMvc;
    
    @MockBean
    private VideoRepo videoRepo;
    
    @MockBean
    private RedisTemplate<String, byte[]> redisTemplate;
    
    @Test
    void testGetPlaylist_NotFound() throws Exception {
        when(videoRepo.findById("unknown-id")).thenReturn(Optional.empty());
        
        mockMvc.perform(get("/stream/unknown-id/playlist.m3u8"))
            .andExpect(status().isNotFound());
    }
}
```

### Load Testing with Locust (External)

See Section 1-3 above. Locust runs as a separate Python process.

---

## 9. Common Performance Optimizations Implemented

| Optimization | Implementation | Impact |
|---|---|---|
| Redis segment caching | `redisTemplate.opsForValue().set()` with 1hr TTL | 10-100x faster repeated requests |
| HLS segmentation | FFmpeg `-c:v copy` (no re-encoding) | 10-100x faster than re-encoding |
| Binary copy | `Files.copy(InputStream, Path)` | Avoids Tomcat temp dir overhead |
| Connection pooling | HikariCP (Spring Boot default) | Reuses DB connections, no connection overhead |
| Lazy loading avoidance | `FetchType.EAGER` on `roleList` | Prevents `LazyInitializationException` in security layer |

---

## 10. Interview Questions on Performance — Prepared Answers

**Q: How did you test the performance of StreamLite?**
> I used Locust, a Python-based load testing tool. I simulated concurrent users requesting HLS playlist and segment files. The test revealed the system was handling 488 RPS for segment files at an average of 45ms response time with zero errors. The bandwidth throughput was approximately 5.14 Gbps — we hit the physical network card limit, proving the software stack was not the bottleneck.

**Q: What is the bottleneck in your current setup?**
> The network interface card (NIC) at ~5 Gbps. The software layer (Spring + Redis + Nginx) performs well below that capacity. To scale further: (1) Upgrade to 10 Gbps NIC, (2) Add multiple servers behind a load balancer, (3) Use a CDN (CloudFront) to distribute the HLS segments globally.

**Q: How does Redis caching improve performance specifically?**
> Without caching, every viewer request for segment `playlist3.ts` reads from disk (5-15ms). With Redis caching after the first request, subsequent requests return from RAM in <1ms. For a popular video with 1000 concurrent viewers, this reduces disk I/O by 99.9% and response time by ~10x.

**Q: What debugging steps did you take for the file upload bug?**
> I identified the bug in phases: (1) `FileNotFoundException` → resolved by controlling the temp directory path. (2) `FileUploadException` → resolved by switching from `transferTo()` to `Files.copy()`. (3) "File in use" → resolved by adding a 300ms sleep for Windows file handle release. Each step involved reading the exact exception, understanding the call chain, and applying the minimal fix. `processBuilder.inheritIO()` was crucial for seeing FFmpeg's own error output.
