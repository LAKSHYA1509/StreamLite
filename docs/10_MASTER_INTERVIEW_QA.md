# 🎯 Master Interview Cheat Sheet — StreamLite

> The ultimate reference. Every hard question, one-liner answers, and code snippet reminders. Read this the night before your interview.

---

## 🏆 The 3-Minute Project Pitch

> *"StreamLite is a full-stack video streaming platform I built from the ground up using Spring Boot 3 and Java 17. It's like a simplified YouTube + Twitch.*
>
> *On the VOD side: users upload MP4 files, which the server automatically segments into HLS format using FFmpeg — splitting them into 10-second MPEG-TS chunks that any browser can stream. I integrated Redis as a caching layer so that popular video segments are served directly from RAM, not disk — which we validated with Locust load testing, hitting 488 RPS at zero errors and ~5 Gbps total throughput.*
>
> *For live streaming: creators get a unique UUID stream key. Viewers watch the live stream and chat in real-time via WebSocket. The chat uses Redis Pub/Sub so it would scale horizontally across multiple servers.*
>
> *Authentication supports both traditional email/password login with BCrypt-hashed passwords and OAuth2 social login via Google and GitHub. The entire security chain is configured with Spring Security 6.*
>
> *I also integrated Kafka for decoupling analytics from the video delivery path, and built Prometheus monitoring via Spring Actuator."*

---

## 📋 Top 50 Interview Questions & Answers

---

### SECTION 1 — Project Fundamentals

**Q1: What does StreamLite do at a high level?**
> A: Full-stack video streaming platform with VOD (upload → FFmpeg HLS segmentation → Redis-cached stream), live streaming with stream keys, real-time WebSocket chat backed by Redis Pub/Sub, OAuth2 + form login authentication, and Prometheus monitoring.

**Q2: What is HLS?**
> A: HTTP Live Streaming — Apple's adaptive protocol that splits video into small segments (`.ts` files) and provides an `.m3u8` playlist manifest. Browsers download only the segments they need. Supports adaptive bitrate, random seek, and CDN distribution.

**Q3: What does FFmpeg do in this project?**
> A: It segments uploaded MP4 files into HLS format using: `ffmpeg -i input.mp4 -c:v copy -c:a copy -hls_time 10 -f hls playlist.m3u8`. The `-c:v copy` flag avoids re-encoding — it's a remux, not transcode — making it fast.

**Q4: Why 10-second segments?**
> A: It's the standard balance: short enough for accurate seeking, long enough to minimize HTTP request overhead. Netflix uses 2-6 seconds for lower latency; YouTube used 10 seconds traditionally.

**Q5: What MIME types do you serve for HLS?**
> A: Playlist: `application/vnd.apple.mpegurl` (`.m3u8`). Segments: `video/mp2t` (`.ts`).

---

### SECTION 2 — Redis Questions

**Q6: How does Redis caching work for video segments?**
> A: Cache key = `"videoId:segmentName"`. On segment request: check Redis first → cache hit returns bytes in <1ms; cache miss reads from disk → stores in Redis with 1-hour TTL → returns bytes.

**Q7: What Redis data structure stores chat history?**
> A: Redis List (`LPUSH`/`LRANGE`). New messages are pushed to the left (newest first). On new connection, last 50 messages are fetched with `LRANGE 0 49` and sent in reverse order (chronological).

**Q8: Why Redis Pub/Sub for chat instead of just broadcasting in WebSocket handler?**
> A: If multiple server instances run behind a load balancer, each instance only knows its own WebSocket connections. Pub/Sub on a shared Redis channel ensures all instances receive and broadcast every message → horizontal scalability.

**Q9: What is the difference between RedisTemplate and StringRedisTemplate?**
> A: `StringRedisTemplate` is pre-configured for `String → String` with UTF-8 serialization. `RedisTemplate<String, byte[]>` handles binary data (video segments). Both are distinct Spring beans.

**Q10: What happens if Redis is unavailable?**
> A: As currently written, the streaming endpoint would throw an exception. Properly, you'd catch `RedisConnectionException` and fall back to disk read. A circuit breaker (Resilience4j) would prevent cascade failures.

---

### SECTION 3 — Spring Security Questions

**Q11: How does BCrypt work?**
> A: BCrypt generates a random 22-character salt, embeds it in the output string, and runs the Blowfish cipher for 2^cost (default 10 = 1024) rounds. Output: `$2a$10$<salt><hash>`. Same password → different hash each time due to random salt. `matches()` extracts the salt and re-hashes for comparison.

**Q12: What is `UserDetails` and why does `User` implement it?**
> A: `UserDetails` is Spring Security's interface representing an authenticated user. It provides `getUsername()`, `getPassword()`, `getAuthorities()`, and account status flags. By implementing it, our `User` entity directly integrates with Spring Security's authentication mechanism without any adapter layer.

**Q13: What is `DaoAuthenticationProvider`?**
> A: It connects a `UserDetailsService` (loads user from DB by username/email) and a `PasswordEncoder` (verifies the password). Spring Security's filter calls it on every form login attempt.

**Q14: How does OAuth2 login work?**
> A: Authorization Code Flow: (1) User clicks "Login with Google" → redirected to Google's auth URL. (2) User approves. (3) Google redirects back with a `code`. (4) Spring exchanges `code` for `access_token` server-to-server. (5) Spring fetches user profile. (6) `OAuthAuthenticationSuccessHandler.onAuthenticationSuccess()` is called → user saved if new → redirect to profile.

**Q15: What is the difference between OIDC and OAuth2?**
> A: OAuth2 = authorization framework (can the app access your data?). OIDC (OpenID Connect) = identity layer on top of OAuth2 (who are you?). Google uses OIDC (returns `id_token`); GitHub uses plain OAuth2 (returns access token + separate profile API call). That's why Google principal is `OidcUser` and GitHub is `OAuth2User`.

**Q16: Why was CSRF disabled?**
> A: The `/upload` endpoint and `/generate-stream-key` are called via JavaScript `fetch()` — not through traditional HTML forms. CSRF tokens would complicate the frontend. For a production REST API, stateless JWT authentication would be used instead, making CSRF irrelevant.

**Q17: What is `@PreAuthorize`?**
> A: Method-level security using Spring AOP. `@PreAuthorize("hasRole('USER')")` wraps the method in an aspect that checks the current principal's authorities before the method executes. Throws `AccessDeniedException` (→ HTTP 403) if check fails.

---

### SECTION 4 — Kafka Questions

**Q18: What is Kafka used for in StreamLite?**
> A: For decoupling analytics events from the video delivery path. Streaming analytics (who requested which segment, cache hit/miss, timestamp) are published to a Kafka topic asynchronously — the video response is not delayed by database writes.

**Q19: What is a Kafka topic?**
> A: A named, append-only, partitioned log of messages. Producers write to it; consumers read from offsets. Messages are retained for a configurable period (default 7 days) regardless of consumption — enabling replay.

**Q20: Producer sending in StreamLite?**
> A: `spring.kafka.producer.bootstrap-servers=localhost:9092` auto-configures `KafkaTemplate`. Calling `kafkaTemplate.send("topic-name", payload)` is async by default — it returns a `CompletableFuture`. The main thread is never blocked.

**Q21: Kafka vs Redis Pub/Sub — when to use which?**
> A: Redis Pub/Sub = ephemeral, low-latency, fire-and-forget (no persistence). Use for: live chat, real-time notifications. Kafka = durable, replayable, high-throughput. Use for: analytics, event sourcing, microservice communication where NO message loss is acceptable.

---

### SECTION 5 — WebSocket Questions

**Q22: What is `TextWebSocketHandler`?**
> A: Spring's base class for text WebSocket handling. Override `afterConnectionEstablished()`, `handleTextMessage()`, `afterConnectionClosed()`. For binary data, extend `BinaryWebSocketHandler`.

**Q23: Why `CopyOnWriteArrayList` for sessions?**
> A: Multiple threads access the list: WebSocket I/O threads add/remove; Redis listener thread iterates to broadcast. `CopyOnWriteArrayList` thread-safely handles this: writes create a new array copy; reads use a consistent snapshot. No `ConcurrentModificationException`.

**Q24: What does `session.getAttributes()` store?**
> A: Per-connection metadata — in this case, the user's random username and color. These are stored in the WebSocket session's attribute map (like a mini session store) and retrieved on every message.

**Q25: How does a WebSocket connection start?**
> A: HTTP GET request with `Upgrade: websocket` header. Server responds `101 Switching Protocols`. TCP connection switches protocols — now bidirectional WebSocket frames over the same TCP socket.

---

### SECTION 6 — JPA / Database Questions

**Q26: What is `@ElementCollection` and when did you use it?**
> A: Maps a `List<BasicType>` to a separate table without creating a full `@Entity`. Used for `User.roleList` → creates `user_role_list(user_user_id, role_list)` table. With `FetchType.EAGER`, roles load immediately with the user.

**Q27: Why `FetchType.EAGER` on roleList?**
> A: Spring Security calls `getAuthorities()` (which uses `roleList`) outside the JPA transaction context. LAZY loading would throw `LazyInitializationException` because the session is closed by then. EAGER loads roles immediately when the User is fetched.

**Q28: What is `ddl-auto=update`?**
> A: Hibernate adds new columns/tables on startup without dropping existing data. Never use in production — use Flyway/Liquibase for versioned migrations.

**Q29: What is the N+1 problem?**
> A: Loading 100 entities with lazy-loaded relationships, then accessing each relationship in a loop → 1 query for all + 100 individual queries = 101 total. Fix with `JOIN FETCH` or `@EntityGraph`.

**Q30: How does Spring Data JPA derive queries from method names?**
> A: `findByEmail(String email)` → `SELECT * FROM users WHERE email = ?`. It parses the method name using keywords: `findBy`, `And`, `Or`, `OrderBy`, `Containing`, `GreaterThan`, etc.

---

### SECTION 7 — Spring Boot Internals

**Q31: What is `@SpringBootApplication`?**
> A: Combines `@Configuration` (mark as config class), `@EnableAutoConfiguration` (auto-configure based on classpath), and `@ComponentScan` (scan current package and sub-packages for beans).

**Q32: What is auto-configuration?**
> A: Spring Boot reads `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`(Spring Boot 3) and applies `@Configuration` classes conditionally based on `@ConditionalOnClass`, `@ConditionalOnMissingBean`, etc. E.g., if `DataSource` class is on classpath, auto-configure a `DataSource` bean.

**Q33: What is `@Component` vs `@Service` vs `@Repository`?**
> A: All three register a bean in the Spring container. `@Service` = business logic layer (semantic). `@Repository` = data access layer (also enables exception translation). `@Component` = generic. From Spring's perspective, they're the same at registration time.

**Q34: How does Spring inject the `Authentication` into a controller method?**
> A: Spring MVC has `AuthenticationHandlerMethodArgumentResolver` which detects parameters of type `Authentication` and resolves them from `SecurityContextHolder.getContext().getAuthentication()` automatically.

**Q35: What is `ProcessBuilder` and how did you use it?**
> A: `ProcessBuilder` starts an external OS process. Used to invoke `segment_video.bat` with the uploaded file path as an argument. `.inheritIO()` routes the child process stdout/stderr to the JVM console. `.waitFor()` blocks until FFmpeg finishes.

---

### SECTION 8 — Architecture & Design

**Q36: Is StreamLite a monolith or microservices?**
> A: Monolith — all features in one JVM. But designed with decoupling: Kafka separates analytics, Redis Pub/Sub decouples chat, REST API separates data from presentation. It could be split into services without rewriting core logic.

**Q37: How would you scale this to millions of users?**
> A: (1) CDN for HLS segments (CloudFront/Cloudflare). (2) Multiple Spring Boot instances behind a load balancer (Redis Pub/Sub already handles stateful chat). (3) S3/MinIO for video storage instead of local disk. (4) Async FFmpeg processing via Kafka. (5) Read replicas for PostgreSQL. (6) Redis Cluster for HA.

**Q38: What would you do differently in production?**
> A: (1) Move secrets to Vault/AWS SSM. (2) `ddl-auto=validate` + Flyway migrations. (3) Async video processing. (4) CDN for static/HLS assets. (5) `@ControllerAdvice` global exception handling. (6) Proper CORS origins instead of `*`.

**Q39: How do you pass data from controller to Thymeleaf?**
> A: Via `Model` parameter — `model.addAttribute("key", value)`. In the template, `${key}` renders the value. For forms, `@ModelAttribute` on both GET (populate form) and POST (receive form data).

**Q40: What is `@CrossOrigin`?**
> A: Adds CORS headers (`Access-Control-Allow-Origin`) to responses. `@CrossOrigin(origins = "*")` allows any domain. Needed when JavaScript on a different origin calls the REST API.

---

### SECTION 9 — Debugging & Problem Solving

**Q41: Describe a bug you fixed in this project.**
> A: The file upload bug — three phases: FileNotFoundException (fixed by controlling temp dir path), FileUploadException (fixed by switching `transferTo()` to `Files.copy()`), file locking on Windows (fixed with 300ms sleep + `processBuilder.inheritIO()` for visibility). Key lesson: understand the entire call chain, not just the surface error.

**Q42: What is `Files.copy()` vs `file.transferTo()`?**
> A: `transferTo()` uses Tomcat's internal mechanism and may write to Tomcat's temp dir first. `Files.copy(inputStream, path)` directly writes from the `InputStream` to the target path, bypassing Tomcat's intermediary — more reliable and portable.

**Q43: What is `Thread.sleep()` doing in the upload code?**
> A: Giving the OS (specifically Windows NTFS) 300ms to release the file lock after `Files.copy()` closes. Without it, the immediately-invoked FFmpeg process would fail with "file in use". This is a known Windows-specific behavior.

---

### SECTION 10 — Spring Security Specific

**Q44: What is the `SecurityFilterChain` bean?**
> A: The central configuration bean that defines the security rules for HTTP requests. It chains together authentication filters, authorization rules, CSRF config, OAuth2 config, form login/logout, and CORS. Java DSL replaces the old `WebSecurityConfigurerAdapter` approach (removed in Spring Security 6).

**Q45: What happens when you access `/user/dashboard` without being logged in?**
> A: Spring Security's `ExceptionTranslationFilter` catches `AccessDeniedException`, determines the user is anonymous, and redirects to the configured login page (`/login`). After successful login, Spring redirects back to the original URL.

**Q46: What is `SessionHelper`?**
> A: A utility class for session operations. Could contain methods to get/clear session attributes like flash messages. The `Message` object (with `type` and `content`) is stored in the session to show one-time registration success alerts.

**Q47: What is `@EnableWebSocket`?**
> A: Registers a `WebSocketHandlerMapping` bean and enables Spring's WebSocket support. Without it, even if you implement `WebSocketConfigurer`, the WebSocket endpoint won't be registered.

---

### SECTION 11 — Monitoring

**Q48: What does Spring Actuator do?**
> A: Provides production-ready endpoints for monitoring and management: `/actuator/health`, `/actuator/metrics`, `/actuator/prometheus`, `/actuator/env`, etc. Configured via `management.endpoints.web.exposure.include=prometheus`.

**Q49: What is Micrometer?**
> A: Micrometer is a metrics facade (like SLF4J for logging but for metrics). It provides a vendor-neutral API for recording metrics (counters, gauges, timers, histograms). The `micrometer-registry-prometheus` dependency bridges Micrometer metrics to Prometheus format.

**Q50: What can Prometheus metrics tell you about StreamLite?**
> A: HTTP request rates and latencies per endpoint, JVM heap/GC metrics, Redis connection pool usage, Kafka producer send rate/latency, segment cache hit/miss ratio (if implemented as a custom counter), database connection pool usage.

---

## 🚀 Key Code Snippets to Remember

### The Redis Cache Pattern
```java
String cacheKey = id + ":" + fileName;
byte[] cached = redisTemplate.opsForValue().get(cacheKey);
if (cached != null) return ResponseEntity.ok().header("Content-Type","video/mp2t").body(cached);
byte[] bytes = Files.readAllBytes(filePath);
redisTemplate.opsForValue().set(cacheKey, bytes, 1, TimeUnit.HOURS);
return ResponseEntity.ok().header("Content-Type","video/mp2t").body(bytes);
```

### The OAuth2 Success Handler Pattern
```java
String provider = ((OAuth2AuthenticationToken)auth).getAuthorizedClientRegistrationId();
// "google" → attributes: email, name, picture
// "github" → attributes: email(nullable), login, avatar_url
User existing = userRepo.findByEmail(email).orElse(null);
if (existing == null) userRepo.save(user);
new DefaultRedirectStrategy().sendRedirect(request, response, "/user/profile");
```

### The WebSocket Broadcast Pattern
```java
// On message receive:
redisTemplate.opsForList().leftPush("chat:history", json);
redisTemplate.opsForList().trim("chat:history", 0, 999);
redisTemplate.convertAndSend("chat", json);
// Redis listener calls:
chatWebSocketHandler.broadcast(new TextMessage(message));
// Which sends to all sessions:
for (WebSocketSession s : sessions) if (s.isOpen()) s.sendMessage(message);
```

### The FFmpeg Command
```bash
ffmpeg -i input.mp4 -c:v copy -c:a copy -start_number 0 -hls_time 10 -hls_list_size 0 -f hls playlist.m3u8
```

### BCrypt Flow
```java
// Register:
user.setPassword(passwordEncoder.encode("rawPassword")); // → $2a$10$...
// Login: handled automatically by DaoAuthenticationProvider
// passwordEncoder.matches("rawPassword", "$2a$10$...") → true
```

---

## 📚 Technologies Summary Card

| Tech | Version | Core Concept | StreamLite Use |
|---|---|---|---|
| Spring Boot | 3.3.1 | Auto-config, embedded Tomcat | Application framework |
| Java | 17 | LTS, modern syntax | Language |
| PostgreSQL | 15+ | ACID relational DB | Users + Videos persistence |
| Hibernate/JPA | 6.x | ORM, JPQL, entity lifecycle | Database abstraction |
| Redis | 7.x | In-memory key-value store | Segment cache + Chat Pub/Sub |
| Kafka | 3.x | Distributed event log | Async analytics |
| Spring Security | 6.x | Filter chain, BCrypt | Auth + OAuth2 |
| OAuth2 | RFC 6749 | Authorization standard | Google/GitHub login |
| WebSocket | RFC 6455 | Full-duplex TCP protocol | Live chat |
| HLS | Apple spec | HTTP-based adaptive streaming | Video delivery |
| FFmpeg | 6.x | Multimedia processing | MP4 → HLS segmentation |
| MPEG-TS | ISO 13818 | Transport stream container | HLS segment format |
| Thymeleaf | 3.1 | Server-side HTML templating | Frontend views |
| TailwindCSS | 3.4 | Utility-first CSS | Styling |
| Lombok | 1.18+ | Code generation | Boilerplate reduction |
| Micrometer | 1.13 | Metrics facade | Prometheus integration |
| Spring Actuator | Included | Management endpoints | /actuator/prometheus |
| Locust | Latest | Python load testing | Performance validation |
