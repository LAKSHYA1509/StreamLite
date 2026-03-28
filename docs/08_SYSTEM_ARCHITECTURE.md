# 🏗️ System Architecture & Spring Boot Internals

> How all the pieces fit together — the big picture an interviewer wants to see when they ask "talk me through your architecture."

---

## 1. The Complete System Architecture Diagram

```
                        ┌─────────────────────────────────────────┐
                        │           StreamLite Application          │
                        │         (Spring Boot 3.3.1 / Java 17)    │
                        │              localhost:8000               │
                        └──────────────────┬──────────────────────-┘
                                           │
              ┌─────────────────┬──────────┴────────┬──────────────────┐
              │                 │                   │                  │
              ▼                 ▼                   ▼                  ▼
    ┌──────────────┐  ┌──────────────────┐  ┌──────────┐   ┌───────────────┐
    │  PostgreSQL   │  │      Redis        │  │  Kafka   │   │  Prometheus   │
    │  Port: 5432   │  │   Port: 6379      │  │ Port:9092│   │ (Monitoring)  │
    │               │  │                  │  │          │   │               │
    │  users table  │  │ Segment cache    │  │ analytics│   │ /actuator/    │
    │  videos table │  │ chat:history     │  │ topic    │   │ prometheus    │
    │  user_role_   │  │ Pub/Sub: chat    │  │          │   │               │
    │  list table   │  │                  │  │          │   │               │
    └──────────────┘  └──────────────────┘  └──────────┘   └───────────────┘

    Browser/Clients:
    ├─ HTTP requests → Tomcat (embedded) → Spring MVC Dispatcher
    ├─ WebSocket ws://host/chat → WebSocketConfig → ChatWebSocketHandler
    └─ OAuth2 → /oauth2/authorization/google → Google → /login/oauth2/code/google
```

---

## 2. Request Processing Pipeline — Spring MVC

Every HTTP request goes through this pipeline:

```
[HTTP Request from Browser]
         │
         ▼
[Embedded Tomcat Server]
    (listens on port 8000)
         │
         ▼
[Spring Security FilterChain]
    ├─ Check: is user authenticated?
    ├─ Is this URL protected? (/user/**)
    ├─ CSRF validation
    └─ OAuth2 filter (if /oauth2/** path)
         │
         ▼ (if request proceeds)
[DispatcherServlet]
    ├─ Matches URL to @Controller / @RestController
    ├─ Resolves method arguments (@PathVariable, @RequestBody, etc.)
    ├─ Calls controller method
    └─ Writes response (JSON / Thymeleaf HTML)
         │
         ▼
[HTTP Response]
```

---

## 3. Spring Boot Auto-Configuration

Spring Boot 3.3.1 auto-configures the application based on what's in the classpath.

| Dependency Found | Auto-Configured |
|---|---|
| `spring-boot-starter-web` | `DispatcherServlet`, embedded Tomcat on port 8080 (overridden to 8000) |
| `spring-boot-starter-data-jpa` | `EntityManagerFactory`, `DataSource`, `TransactionManager` |
| `spring-boot-starter-security` | `SecurityFilterChain` with form login, HTTP basic |
| `spring-boot-starter-data-redis` | `RedisConnectionFactory`, `StringRedisTemplate` |
| `spring-kafka` | `KafkaTemplate`, `KafkaAdmin` |
| `spring-boot-starter-websocket` | `TomcatWebSocketServletWebServerCustomizer` |
| `micrometer-registry-prometheus` | `PrometheusMeterRegistry`, `/actuator/prometheus` endpoint |

**`@SpringBootApplication`** = `@Configuration` + `@EnableAutoConfiguration` + `@ComponentScan`

```java
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

---

## 4. Dependency Injection — How Spring Wires Everything Together

Spring uses **Inversion of Control (IoC)** — instead of objects creating their dependencies, Spring injects them.

```java
@Autowired
private VideoRepo videoRepo;  // Spring finds the VideoRepo bean and injects it
```

**Bean creation order (simplified):**
1. `@Configuration` classes are processed first → `RedisConfig`, `SecurityConfig`
2. `@Component`, `@Service`, `@Repository`, `@Controller` are scanned
3. Dependencies are injected (topological order — dependencies before dependents)
4. `ApplicationContext` is fully initialized
5. Embedded Tomcat starts listening

**Bean scopes:**
- `@Scope("singleton")` (default) — one instance per application context
- `@Scope("prototype")` — new instance every time it's requested
- `@Scope("request")` — one instance per HTTP request
- `@Scope("session")` — one instance per HTTP session

`ChatWebSocketHandler` is a `@Component` (singleton) — one instance manages ALL WebSocket sessions.

---

## 5. Data Flow — Upload to Stream (Complete)

```
1. User selects video, submits form
        │
        ▼ POST /upload (multipart/form-data)
2. Tomcat buffers the upload to ${user.dir}/videos/tmp/
        │
        ▼
3. StreamingController.uploadSpecificVideo()
        ├─ Validates .mp4 extension
        ├─ Files.copy() → videos/input/videoplayback.mp4
        ├─ Thread.sleep(300ms)  [Windows file handle release]
        └─ ProcessBuilder → segment_video.bat videoplayback.mp4
                │
                ▼ (FFmpeg runs synchronously)
4. FFmpeg creates:
        ├─ videos/output/playlist.m3u8
        ├─ videos/output/playlist0.ts (0-10 seconds)
        ├─ videos/output/playlist1.ts (10-20 seconds)
        └─ ...
        │
        ▼
5. Video entity saved to PostgreSQL:
        { id: "uuid", title: "videoplayback.mp4", hlsPath: "videos/output/playlist.m3u8" }
        │
        ▼
6. HTTP 200 response: { videoId, playlist }
        │
        ▼
7. Client gets the videoId

---VIEWER REQUESTS VIDEO---

8. Browser requests: GET /stream/{videoId}/playlist.m3u8
        │
        ▼
9. StreamingController reads playlist from disk (videos/output/playlist.m3u8)
   Returns with Content-Type: application/vnd.apple.mpegurl
        │
        ▼
10. HLS.js / Video.js parses playlist, identifies segment URLs
        │
        ▼
11. Browser requests: GET /stream/{videoId}/playlist0.ts
        │
        ▼
12. Redis lookup: key="videoId:playlist0.ts"
        ├─ HIT → return bytes from Redis (<1ms)
        └─ MISS → read from disk → store in Redis (1hr TTL) → return bytes
        │
        ▼
13. Browser decodes MPEG-TS, renders video in player
        │
        ▼  (repeats for each segment)
14. Cycle continues until #EXT-X-ENDLIST reached (watch complete)
```

---

## 6. Monitoring — Spring Actuator + Prometheus

### `application.properties`
```properties
management.endpoints.web.exposure.include=prometheus
```

### What This Exposes

**`GET /actuator/prometheus`** returns metrics in Prometheus format:

```
# HELP http_server_requests_seconds Duration of HTTP server request handling
# TYPE http_server_requests_seconds summary
http_server_requests_seconds_count{exception="None",method="GET",outcome="SUCCESS",status="200",uri="/stream/{id}/{fileName}"} 4820
http_server_requests_seconds_sum{...} 24.35

# HELP jvm_memory_used_bytes
jvm_memory_used_bytes{area="heap"} 1.24e+08

# HELP redis_commands_duration_seconds
redis_commands_duration_seconds_sum 0.045
```

Prometheus scrapes this endpoint every 15 seconds, storing time-series data. Grafana can then visualize:
- Requests per second per endpoint
- P95/P99 response latencies
- JVM heap usage, GC pressure
- Redis connection pool metrics
- Kafka producer send rates

### Custom Metrics (potential addition)

```java
@Autowired
private MeterRegistry meterRegistry;

// In StreamingController:
Counter cacheHits = Counter.builder("cache.hits")
    .tag("type", "segment")
    .register(meterRegistry);

cacheHits.increment();  // Each time cache hit occurs
```

---

## 7. Thymeleaf — Server-Side Rendering

Thymeleaf is Spring Boot's default templating engine. It renders HTML on the server with data injected from the controller.

### Example flow for home page:

```java
// PageController.java
@GetMapping("/")
public String homePage(Model model) {
    List<Video> videos = videoRepo.findAll();
    model.addAttribute("videos", videos);  // Pass to template
    return "home";  // → templates/home.html
}
```

```html
<!-- templates/home.html (Thymeleaf) -->
<div th:each="video : ${videos}">
    <h3 th:text="${video.title}">Video Title</h3>
    <a th:href="@{'/stream/' + ${video.id} + '/playlist.m3u8'}">Watch</a>
</div>
```

**Key Thymeleaf syntax:**
- `th:each` — Loop (like `forEach`)
- `th:text` — Set text content
- `th:href` — Dynamic URL
- `th:if` / `th:unless` — Conditionals
- `th:object` + `th:field` — Form binding

---

## 8. Spring Security's `Authentication` Object

```java
// In any @Controller method, Spring injects the current user:
@RequestMapping("/profile")
public String userProfile(Model model, Authentication authentication) {
    // authentication.getName() → "user@example.com" (the username/email)
    // authentication.getPrincipal() → User object (our entity)
    // authentication.getAuthorities() → [ROLE_USER]
    // authentication.isAuthenticated() → true
    return "user/profile";
}
```

**Three types of principal in StreamLite:**

```java
Object principal = authentication.getPrincipal();

if (principal instanceof OidcUser) {
    // Google login (OIDC = OpenID Connect, a superset of OAuth2)
    email = ((OidcUser) principal).getEmail();
} else if (principal instanceof OAuth2User) {
    // GitHub login
    email = ((OAuth2User) principal).getAttribute("email");
} else {
    // Form login — principal is a UserDetails object
    email = authentication.getName();  // Returns email (our username)
}
```

---

## 9. CORS Configuration

```java
httpSecurity.cors(withDefaults());
```

CORS (Cross-Origin Resource Sharing) allows JavaScript from `http://frontend.example.com` to call APIs at `http://api.example.com`. Without CORS, browsers block cross-origin requests.

The `VideoController` also has:
```java
@CrossOrigin(origins = "*")
```

This controller-level annotation adds CORS headers to all responses from `VideoController`. In a production app, replace `"*"` with your actual frontend domain.

---

## 10. Environment & Configuration

### `application.properties` — All Config Explained

```properties
# Application name (used by Spring Cloud, Prometheus labels, etc.)
spring.application.name=StreamLite

# Port — override default 8080
server.port=8000

# File upload limits — 50MB max for videos
spring.servlet.multipart.max-request-size=50MB
spring.servlet.multipart.max-file-size=50MB

# PostgreSQL connection
spring.datasource.url=jdbc:postgresql://localhost:5432/StreamLite
spring.datasource.username=postgres
spring.datasource.password=la@150903
spring.datasource.driver-class-name=org.postgresql.Driver
spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect

# Hibernate logging (for debugging SQL queries)
logging.level.org.hibernate.SQL=DEBUG
logging.level.org.hibernate.type=TRACE

# Schema management
spring.jpa.hibernate.ddl-auto=update

# OAuth2 — Google
spring.security.oauth2.client.registration.google.client-id=354676508759-...
spring.security.oauth2.client.registration.google.client-secret=GOCSPX-...
spring.security.oauth2.client.registration.google.scope=profile,email

# OAuth2 — GitHub
spring.security.oauth2.client.registration.github.client-id=Iv23liwBKD9wYlcyC0Iz
spring.security.oauth2.client.registration.github.client-secret=616a3ef9...
spring.security.oauth2.client.registration.github.scope=email,profile

# Redis
spring.redis.host=localhost
spring.redis.port=6379
spring.redis.password=la@150903

# Kafka
spring.kafka.producer.bootstrap-servers=localhost:9092

# Actuator — only expose prometheus endpoint publicly
management.endpoints.web.exposure.include=prometheus
```

---

## 11. Interview Questions on Architecture — Prepared Answers

**Q: Walk me through the architecture of StreamLite.**
> "StreamLite is a Spring Boot 3 monolith running on embedded Tomcat. The core feature is HLS video streaming: videos are uploaded, segmented by FFmpeg into 10-second MPEG-TS chunks, and served via REST endpoints with Redis caching to handle concurrent viewers. Live chat uses WebSocket with Redis Pub/Sub for horizontal scalability. Authentication supports form login (BCrypt + Spring Security) and OAuth2 (Google/GitHub). PostgreSQL stores user and video metadata. Kafka decouples analytics from the streaming path. Prometheus/Actuator provide monitoring."

**Q: How is your application layered?**
> Standard layered architecture:
> - **Presentation:** `@Controller` classes + Thymeleaf templates (MVC) and `@RestController` classes (REST API)
> - **Service:** `UserService`/`UserServiceimpl` — business logic, password encoding, role assignment
> - **Repository:** `UserRepo`/`VideoRepo` (Spring Data JPA) — data access
> - **Infrastructure:** `SecurityConfig`, `RedisConfig`, `WebSocketConfig` — cross-cutting concerns

**Q: What would you change to scale this to production?**
> 1. **Externalize video storage** to AWS S3 or MinIO instead of local disk
> 2. **Horizontal scaling** — Run multiple instances behind a load balancer (Redis Pub/Sub already handles chat)
> 3. **Async FFmpeg processing** — Use a message queue (Kafka) to trigger processing instead of synchronous `process.waitFor()`
> 4. **CDN** — Serve HLS segments from CloudFront/Nginx cache instead of directly from Spring
> 5. **Secrets management** — Move credentials from `application.properties` to Vault or AWS Secrets Manager
> 6. **Database** — Use connection pooling (HikariCP is already included in Spring Boot)

**Q: Is this a monolith or microservices?**
> It's a **monolith** — all features (streaming, auth, chat, video management) run in a single JVM process. However, it's designed with **microservices principles** in mind: Kafka decouples analytics, Redis Pub/Sub decouples chat from the server instance, and the REST API (`/api/videos`) separates data from presentation. It could be split into microservices (Streaming Service, Auth Service, Chat Service) without rewriting the core logic.
