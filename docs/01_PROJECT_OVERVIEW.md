# 📺 StreamLite — Project Overview (Interview Master Doc)

> **Author:** Lakshya Bhardwaj | **Stack:** Spring Boot 3.3.1, Java 17, PostgreSQL, Redis, Kafka, WebSocket, HLS, FFmpeg, TailwindCSS, OAuth2

---

## 1. What Is StreamLite?

**StreamLite** is a full-stack **video streaming platform** — think of it as a simplified YouTube + Twitch built from scratch using modern Java backend technologies. It supports:

- **VOD (Video on Demand):** Upload an MP4 → automatically segment it into HLS format with FFmpeg → stream it to browsers via HTTP.
- **Live Streaming:** Creators generate a unique stream key, use it to broadcast, and viewers watch the live feed.
- **Live Chat:** Every viewer can chat in real-time during a stream. Messages are saved in Redis and broadcast via WebSocket.
- **Authentication:** Users can register with email/password (BCrypt hashed) OR log in with Google/GitHub via OAuth2.
- **Creator Dashboard:** Authenticated users can generate stream keys and manage their content.
- **Monitoring:** The app exposes metrics to Prometheus via Spring Actuator.

---

## 2. Why Was This Project Built? (The "Big Picture")

The project was built to deeply understand:
1. **How video streaming works at the protocol level** (HLS, MPEG-TS segments)
2. **How to handle high-concurrency** with Redis caching so video segments don't hit the disk on every request
3. **How to build real-time features** (live chat) using WebSocket + Redis Pub/Sub (so it could scale to multiple server instances)
4. **How to integrate OAuth2** for social login in a production-grade Spring Security setup
5. **How to use Kafka** for decoupling non-critical asynchronous tasks (analytics) from the critical video delivery path
6. **How to monitor a production system** using Prometheus + Spring Actuator

---

## 3. Core Features at a Glance

| Feature | Technology | Key Classes |
|---|---|---|
| Video upload & segmentation | FFmpeg, Spring MVC, `MultipartFile` | `StreamingController.java` |
| HLS streaming with Redis cache | Redis, Spring Data Redis | `StreamingController.java`, `RedisConfig.java` |
| User registration (email+password) | Spring Security, BCrypt, JPA | `PageController.java`, `UserServiceimpl.java` |
| Social Login (Google, GitHub) | Spring OAuth2 Client | `OAuthAuthenticationSuccessHandler.java` |
| Live chat (real-time) | WebSocket, Redis Pub/Sub | `ChatWebSocketHandler.java`, `RedisMessageSubscriber.java` |
| Stream key generation | UUID, Spring Security | `UserController.java` |
| Video CRUD API | Spring REST, JPA | `VideoController.java` |
| Monitoring | Spring Actuator, Micrometer, Prometheus | `application.properties` |
| Frontend rendering | Thymeleaf, TailwindCSS | `templates/` |

---

## 4. The Problem StreamLite Solves

### Problem 1: Raw video files can't be streamed efficiently
A raw `.mp4` is one giant file. If the viewer skips to 45 minutes in, the server must either:
- Stream the whole file from the beginning (wasteful)
- Support HTTP byte-range requests (complex)

**Solution:** HLS (HTTP Live Streaming) → FFmpeg splits the MP4 into 10-second `.ts` segments + a `.m3u8` playlist. The browser only downloads the segments it needs.

### Problem 2: Every video segment hit reading from disk is slow
On high traffic, reading the same segment file from disk for every viewer is a bottleneck.

**Solution:** Redis cache — after the first read, the segment is stored in Redis (in-memory). Subsequent requests return from cache (microseconds vs milliseconds).

### Problem 3: Live chat can't scale with a single server
A naive WebSocket implementation stores sessions in-memory. If you add a second server, viewers on Server A can't see messages from viewers on Server B.

**Solution:** Redis Pub/Sub — all servers subscribe to the same `chat` channel. When any server receives a message, it publishes to Redis, which broadcasts it to all subscribers (all server instances).

### Problem 4: Analytics would slow down video delivery
Logging who watched what, when, and for how long requires database writes. Doing this synchronously during video segment delivery adds latency.

**Solution:** Kafka — fire and forget the analytics event to a Kafka topic; a separate consumer picks it up asynchronously. Video delivery is never blocked.

---

## 5. Project Structure

```
StreamLite/
├── src/main/java/com/scm/
│   ├── Application.java                    ← Spring Boot entry point
│   ├── config/
│   │   ├── SecurityConfig.java             ← Spring Security chain (BCrypt + OAuth2)
│   │   ├── OAuthAuthenticationSuccessHandler.java ← Google/GitHub post-login logic
│   │   ├── WebSocketConfig.java            ← Registers /chat WebSocket endpoint
│   │   ├── ChatWebSocketHandler.java       ← Manages WebSocket sessions + Redis pub
│   │   └── RedisConfig.java                ← RedisTemplate + Pub/Sub listener container
│   ├── contollers/
│   │   ├── StreamingController.java        ← HLS stream endpoint + video upload
│   │   ├── VideoController.java            ← REST CRUD API for Video entity
│   │   ├── UserController.java             ← Stream key generation endpoint
│   │   ├── PageController.java             ← Thymeleaf view navigation + registration
│   │   ├── PlayerController.java           ← Video player views
│   │   ├── AuthController.java             ← Auth-related views
│   │   └── RootController.java             ← Root redirect
│   ├── entities/
│   │   ├── User.java                       ← JPA entity + implements UserDetails
│   │   ├── Video.java                      ← JPA entity for video metadata
│   │   ├── ChatMessage.java                ← POJO for WebSocket chat messages
│   │   └── Providers.java                  ← Enum: SELF, GOOGLE, GITHUB, LINKEDIN
│   ├── forms/
│   │   └── UserForm.java                   ← Registration form DTO with validation
│   ├── helpers/
│   │   ├── AppConstants.java               ← Constants (ROLE_USER, PAGE_SIZE, etc.)
│   │   ├── Helper.java                     ← Utility methods
│   │   ├── Message.java                    ← Flash message builder
│   │   ├── MessageType.java                ← Enum for message severity
│   │   ├── ResourceNotFoundException.java  ← Custom 404 exception
│   │   └── SessionHelper.java              ← Session utility
│   ├── repsitories/
│   │   ├── UserRepo.java                   ← JpaRepository<User,String> + custom queries
│   │   └── VideoRepo.java                  ← JpaRepository<Video,String>
│   └── services/
│       ├── UserService.java                ← Service interface
│       ├── RedisMessageSubscriber.java     ← Redis sub → WebSocket broadcast bridge
│       └── impl/
│           ├── UserServiceimpl.java        ← CRUD implementation
│           └── SecurityCustomUserDetailService.java ← UserDetailsService impl
├── src/main/resources/
│   ├── application.properties              ← All config (DB, Redis, Kafka, OAuth2)
│   ├── static/
│   │   ├── css/ (TailwindCSS output)
│   │   └── js/ (admin.js, contacts.js, script.js)
│   └── templates/ (Thymeleaf HTML templates)
├── segment_video.bat / segment_video.sh    ← FFmpeg HLS segmentation scripts
├── pom.xml                                 ← Maven dependencies
└── docs/                                   ← Bug reports, Kafka rationale, load test results
```

---

## 6. Key Design Decisions (Be Ready to Justify These)

### ✅ Why HLS instead of raw MP4 streaming?
**HLS (HTTP Live Streaming)** is an Apple-standard protocol supported by all modern browsers. It allows:
- **Adaptive bitrate streaming** (switch quality based on network)
- **Random access** — jump to any point without downloading the full file
- **Standard CDN compatibility** — `.m3u8` and `.ts` files are static → perfect for CDNs
- **Resumability** — each segment is an independent HTTP request

### ✅ Why Redis for caching video segments?
Redis is an **in-memory key-value store**. Video segments are binary blobs. Storing them in Redis with a 1-hour TTL means the first viewer pays the disk I/O cost; every subsequent viewer gets sub-millisecond response times from RAM.

### ✅ Why Redis Pub/Sub for live chat?
Redis Pub/Sub decouples message publishing from message delivery. If StreamLite runs on 3 servers:
- Server 1 receives a message → publishes to Redis `chat` channel
- **All 3 servers** receive the Redis message → each broadcasts to their WebSocket clients
- Result: All viewers, regardless of which server they're connected to, see all messages.

### ✅ Why PostgreSQL over MySQL?
PostgreSQL was chosen because:
- Better support for complex queries, JSON fields, full-text search
- Open-source with enterprise features
- Spring Data JPA works identically with both — switching is just a `pom.xml` + properties change

### ✅ Why Spring Security with BCryptPasswordEncoder?
BCrypt is the industry standard for password hashing because:
- It's **adaptive** — the work factor can be increased as hardware gets faster
- It includes a **built-in salt** — same password hashed twice gives different results → prevents rainbow table attacks
- Spring Security's `DaoAuthenticationProvider` handles the comparison automatically

---

## 7. Load Testing Results (Locust)

The system was load tested using **Locust** with simulated concurrent viewers requesting HLS segments via Nginx.

| Metric | Value |
|---|---|
| Average response time | **8ms** (playlist), **45ms** (segments) |
| Error rate | **0%** |
| Peak RPS (requests/sec) | **488** |
| Throughput bandwidth | **~5.14 Gbps** |
| Bottleneck | Physical NIC (Network Interface Card) limit |

**Key insight:** The server hit the **physical network limit** (~5 Gbps), not a software bottleneck. This proves the architecture is correctly optimized at the application level. Scaling beyond this would require either a 10 Gbps NIC or horizontal scaling (multiple Nginx servers behind a load balancer).

---

## 8. Technologies Used — Interview Quick Reference

| Technology | Version | Why Used |
|---|---|---|
| **Spring Boot** | 3.3.1 | Auto-configuration, embedded Tomcat, production-ready |
| **Java** | 17 | LTS version, modern features (records, sealed classes, text blocks) |
| **PostgreSQL** | Latest | Relational DB for Users and Videos |
| **Spring Data JPA** | Included | ORM abstraction over Hibernate for CRUD |
| **Redis** | Latest | Segment caching + Live chat Pub/Sub |
| **Apache Kafka** | Included via spring-kafka | Async analytics event streaming |
| **Spring Security** | Included | Auth filter chain, BCrypt, CSRF, session |
| **OAuth2 Client** | Included | Google + GitHub social login |
| **WebSocket** | Included | Real-time bidirectional chat |
| **FFmpeg** | System PATH | Video transcoding + HLS segmentation |
| **Thymeleaf** | Included | Server-side HTML rendering |
| **TailwindCSS** | 3.4.18 | Utility-first CSS framework |
| **Lombok** | Latest | Reduces boilerplate (@Getter, @Setter, @Builder, @Data) |
| **Spring Actuator** | Included | Health, metrics endpoints |
| **Micrometer + Prometheus** | Included | Production monitoring |
| **Locust** | Latest | Python-based load testing framework |

---

## 9. How to Run StreamLite

```bash
# 1. Start PostgreSQL (must have StreamLite database)
# 2. Start Redis on port 6379
# 3. Start Kafka on localhost:9092

# 4. Build and run the Spring Boot app
./mvnw spring-boot:run

# 5. Watch TailwindCSS (in a separate terminal)
npm run build-css

# The app runs on http://localhost:8000
```

---

## 10. Interview Talking Points

> "StreamLite is a full-stack video streaming platform I built from scratch using Spring Boot 3. The core feature is HLS-based video streaming where videos are segmented using FFmpeg into 10-second MPEG-TS chunks. Redis caches these segments in memory to handle high concurrency, and we validated this with Locust load testing which showed 488 RPS with 0% error rate and ~5 Gbps throughput. The live chat uses WebSocket + Redis Pub/Sub so it's horizontally scalable. Authentication supports both form login with BCrypt-hashed passwords and OAuth2 social login via Google and GitHub. I also integrated Kafka for async analytics decoupled from the critical video delivery path, and Prometheus/Actuator for monitoring."
