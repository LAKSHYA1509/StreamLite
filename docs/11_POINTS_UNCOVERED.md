# 🔥 Points Uncovered — Everything Your Resume Claims You Can Speak To

> This document is built specifically around your resume bullet points. Every phrase is dissected so you can defend it with precision, depth, and real code examples from the project.

---

## YOUR RESUME BULLETS (EXACT TEXT):

> **Bullet 1:** *"Built a research-backed, hybrid Live + VOD backend capable of handling high-concurrency streaming workloads on resource-constrained infrastructure using Spring Boot, FFmpeg, Redis, and Apache Kafka."*
>
> **Bullet 2:** *"Designed event-driven architecture separating control/data planes for scalability and fault isolation."*
>
> **Bullet 3:** *"Enabled low-latency video delivery, real-time chat via WebSockets, and fault-tolerant analytics pipelines utilizing Kafka producers and consumers."*

Every single word of these bullets is analyzed below. Read this front to back.

---

# BULLET 1 — Deep Dissection

## 1.1 "Research-Backed"

This means you made **deliberate, justified technology choices** based on understanding trade-offs. Here's the research behind each choice:

### Why HLS over DASH or raw HTTP range requests?
- **HLS** is the only protocol with native support in Safari (iOS/macOS doesn't support MSE-based DASH without a polyfill)
- `.ts` segments are **independently decodable** — no need for init segment like fMP4/DASH
- `.m3u8` playlists are plain text — CDN-friendly, human-readable, trivially debuggable
- **Research decision:** HLS for maximum browser compatibility without JavaScript polyfills

### Why Redis for caching over Caffeine/Guava in-process cache?
- **In-process caches** (Caffeine) are lost when the JVM restarts and cannot be shared across multiple server instances
- **Redis** is shared — multiple Spring Boot instances all hit the same cache
- **Research decision:** Redis for cache that scales horizontally

### Why 10-second HLS segments vs 2-second vs 30-second?
- 2 seconds → great latency, but more HTTP requests, more playlist overhead, harder for slow networks
- 30 seconds → very few requests, but poor seek accuracy, high startup latency
- 10 seconds → **the HLS RFC recommendation** for VOD, balanced trade-off
- For your live stream: you actually used **2-second segments** (see `start_live.bat`)

### The actual live stream FFmpeg flags analyzed:
```bash
# From start_live.bat:
ffmpeg \
  -fflags +genpts \          # Generate presentation timestamps if missing
  -i rtmp://localhost/live/my_stream_key \   # Input: RTMP stream from OBS
  -c:v libx264 \             # Re-encode to H.264 (RTMP may come in different codec)
  -c:a aac \                 # Re-encode audio to AAC
  -af aresample=async=1 \    # Audio resample filter: sync audio to video clock (prevents A/V drift)
  -f hls \                   # Output format: HLS
  -hls_time 2 \              # 2-second segments (low latency for LIVE)
  -hls_playlist_type event \ # "event" type = keep all segments in playlist (live event)
  "C:\nginx-rtmp\...\live.m3u8"
```

**Key distinction between VOD and Live FFmpeg commands:**

| Flag | VOD (`segment_video.bat`) | Live (`start_live.bat`) |
|---|---|---|
| Input | Local `.mp4` file | `rtmp://localhost/live/stream_key` |
| Video codec | `-c:v copy` (no re-encode) | `-c:v libx264` (re-encode from RTMP) |
| Audio codec | `-c:a copy` | `-c:a aac` |
| Segment time | `10` seconds | `2` seconds |
| Playlist type | No flag (implicit VOD) | `event` (live) |
| `-fflags +genpts` | Not needed | Required (RTMP streams may lack PTS) |

---

## 1.2 "Hybrid Live + VOD Backend"

**Hybrid** = the SAME infrastructure (Spring Boot + Redis + FFmpeg + Nginx) serves **both** streaming modes:

### VOD Path:
```
Upload MP4 → FFmpeg (segment_video.bat) → .ts files on disk
                                         → Redis cache
                                         → Spring Boot serves /stream/{id}/{segment}
```

### Live Path:
```
OBS (broadcaster) → RTMP → Nginx-RTMP module
                            → FFmpeg (start_live.bat) → live.ts segments every 2s
                                                       → Nginx serves from /hls/
                            → HLS player polls live.m3u8 every 2s
```

**The Nginx-RTMP Module** is the key piece making live work:
- Nginx has a **`nginx-rtmp`** module that accepts RTMP connections (what OBS sends)
- On receiving the stream, it can trigger FFmpeg transcoding and serve HLS
- The `start_live.bat` runs FFmpeg pointed at `rtmp://localhost/live/my_stream_key`
- FFmpeg writes 2-second `.ts` segments to `C:\nginx-rtmp\...\html\hls\`
- Nginx static file serving then delivers these to viewers

### Why RTMP for live ingest?
RTMP (Real-Time Messaging Protocol) is the industry standard for encoder-to-server delivery:
- OBS, Streamlabs, all broadcast software support RTMP natively
- Low-latency, TCP-based, guaranteed delivery
- Your stream key (`UUID`) is part of the RTMP URL: `rtmp://host/live/{stream_key}`

### The Stream Key Connection:
```java
// UserController.java — generates the RTMP stream key
String streamKey = UUID.randomUUID().toString();
user.setStreamKey(streamKey);
// The creator uses this in OBS:
// rtmp://your-server/live/{streamKey}
// start_live.bat receives it at rtmp://localhost/live/my_stream_key
```

---

## 1.3 "High-Concurrency Streaming Workloads"

**Concurrency in streaming** means many viewers requesting segments simultaneously. Here's how each tech layer handles it:

### How Tomcat handles concurrency:
Spring Boot's embedded Tomcat uses a **thread pool** (default 200 threads) to handle concurrent HTTP requests. Each incoming segment request gets its own thread from the pool.

### How Redis multiplies capacity:
Without Redis: 488 concurrent segment requests = 488 disk reads (I/O bound).
With Redis: after the first viewer fetches each segment, subsequent 487 requests hit RAM, not disk. I/O bottleneck eliminated.

### The actual concurrency math from your load test:
```
488 Requests per Second (RPS)
Each segment takes ~10 seconds to play
→ 488 × 10 = ~4,880 concurrent viewers can be supported before throughput drops
```

### Why `@Controller` is not `@RestController` for streaming?
`StreamingController` is annotated `@Controller` but the methods return `ResponseEntity<byte[]>` directly. This works because `ResponseEntity` bypasses view resolution — Spring writes the bytes directly to the HTTP response body. No Thymeleaf involved.

---

## 1.4 "Resource-Constrained Infrastructure"

This means you made it work on a **single machine / limited hardware**, NOT a distributed cloud cluster.

### What "resource-constrained" means specifically:
- Single server (not a fleet)
- No managed CDN
- No cloud object storage (S3/GCS) — just local disk
- 5 Gbps NIC (consumer-grade hardware)
- Local Redis (not Redis Cluster)
- Local Kafka (single broker)
- Local PostgreSQL (not RDS)

### How you optimized for resource constraints:

| Constraint | Optimization | How |
|---|---|---|
| Limited RAM | Redis TTL | 1-hour TTL evicts unused segments automatically |
| Limited CPU | `-c:v copy` in FFmpeg | Zero CPU for transcoding — just remux |
| Limited bandwidth | HLS segmentation | Browser only downloads the seconds it's watching |
| Limited disk I/O | Redis caching | Once cached, disk isn't touched again |
| Single server | Redis Pub/Sub | Future-proof: would work if scaled to multiple servers |

**The 5 Gbps result proves the software is optimized** — you hit the hardware ceiling, not a software one.

---

# BULLET 2 — Deep Dissection

## 2.1 "Event-Driven Architecture"

**Event-driven** means components communicate via **asynchronous events** rather than direct synchronous calls.

### Traditional (non-event-driven) approach:
```
Player page loads → PlayerController → writes to "analytics" DB table → returns HTML
                                            ↑ This blocks. DB slow? Player page slow.
```

### Event-driven approach (what StreamLite does):
```java
// PlayerController.java — the ACTUAL Kafka producer code:
@GetMapping("/player/{videoId}")
public String showPlayerPage(@PathVariable String videoId, Model model) {
    model.addAttribute("videoId", videoId);
    
    // This is the event-driven part:
    String userId = "user_anonymous"; 
    String eventMessage = String.format(
        "PLAYBACK_STARTED: User '%s' started watching video '%s'", 
        userId, videoId
    );
    
    kafkaTemplate.send("analytics-events", eventMessage);
    // ↑ Returns immediately — doesn't wait for anything
    
    return "player";  // Player page renders while Kafka handles the event
}
```

**This is the actual event that gets fired.** When `GET /player/{videoId}` is called, the page renders immediately, and *simultaneously* in the background, Kafka receives `"PLAYBACK_STARTED: User 'user_anonymous' started watching video 'abc-123'"`.

### The Event-Driven Flow:
```
Browser: GET /player/abc-123
                │
                ▼
PlayerController.showPlayerPage()
                ├─ model.addAttribute("videoId", "abc-123")
                ├─ kafkaTemplate.send("analytics-events", "PLAYBACK_STARTED: ...") → ASYNC, instant
                └─ return "player"  ← Page renders without waiting
                
                        ... in the background, at Kafka's own pace ...
                        
[Kafka "analytics-events" topic]
    ← Analytics consumer app reads the event
    → Writes to analytics database / data warehouse
    → Increments view counter
    → Updates recommendation engine
```

---

## 2.2 "Separating Control Plane and Data Plane"

This is a **networking/systems design concept** applied to streaming. It's one of the most impressive things you can say.

### Definition:
- **Control Plane:** The "brain" — routing, configuration, session management, signaling
- **Data Plane:** The "muscle" — actual data transfer, packet forwarding, media delivery

### How StreamLite separates them:

**Control Plane (Spring Boot):**
- `POST /upload` — initiates the video pipeline (says "start processing this video")
- `GET /stream/{id}/playlist.m3u8` — serves the manifest that tells the player what segments exist
- `POST /generate-stream-key` — issues credentials (says "you can stream")
- `POST /authenticate` — session management, authentication
- `WebSocket /chat` — signaling channel for chat (metadata + coordination)
- `GET /player/{videoId}` — fires `PLAYBACK_STARTED` Kafka event (signals the analytics plane)

**Data Plane (Nginx + FFmpeg + Redis + Kafka):**
- Nginx serving `.ts` segment files directly (raw bytes)
- Redis holding cached segment bytes in RAM
- FFmpeg piping RTMP bytes into `.ts` files
- Kafka storing and forwarding event byte streams

### Why this separation matters:
- **Fault isolation:** If your analytics (data plane) Kafka consumer crashes, video streaming continues. Control plane doesn't care.
- **Scalability:** You can scale the data plane (more Nginx nodes, Redis Cluster) independently of the control plane (Spring Boot).
- **Observability:** You can monitor each plane separately — Spring Actuator for control plane health, Promtheus for data plane metrics.

### The practical example in your code:
```java
// StreamingController — control plane decides WHERE data is, data plane delivers it
@GetMapping("/stream/{id}/playlist.m3u8")
public ResponseEntity<byte[]> getHlsPlaylist(@PathVariable String id) {
    // Control plane: looks up video in PostgreSQL → knows the file path
    Video video = videoRepo.findById(id).orElseThrow(...);
    Path playlistPath = Paths.get(video.getHlsPath());
    
    // Hands off to data plane: reads bytes and streams them
    byte[] playlistBytes = Files.readAllBytes(playlistPath);
    return ResponseEntity.ok()
        .header("Content-Type", "application/vnd.apple.mpegurl")
        .body(playlistBytes);
}
// The actual video chunks (data plane) are served similarly, but through Redis
```

---

## 2.3 "Scalability"

**Scalability** = ability to handle increased load by adding resources. StreamLite achieves scalability through stateless design and shared state in external systems.

### What makes it horizontally scalable:

**Stateless HTTP handlers:**
Spring Boot controllers are stateless — they don't store any per-user data in the JVM. All state is in PostgreSQL (users, videos) or Redis (segments, chat history). This means you can run 10 instances of the Spring Boot app behind a load balancer — any instance can handle any request.

**Redis Pub/Sub for shared chat:**
Even with 5 server instances, the `chat` Redis channel ensures all viewers see all messages regardless of which server their WebSocket is connected to.

**Redis as shared cache:**
All server instances share the same Redis cache — no duplication, no cache-miss storm when scaling up.

**Kafka as durable event bus:**
If the analytics consumer crashes, Kafka retains the events. When it restarts, it resumes from the committed offset — zero data loss.

---

## 2.4 "Fault Isolation"

**Fault isolation** = one component failing doesn't cascade and bring down everything else.

### How each potential failure is isolated in StreamLite:

| Component Failure | Impact | Isolated? |
|---|---|---|
| Redis down | Cache misses → slower segment delivery (disk reads), chat history unavailable | ✅ Core streaming still works |
| Kafka down | Analytics events lost (not persisted in this version) | ✅ Video streaming unaffected |
| Analytics consumer crash | Analytics pipeline stalls | ✅ Kafka retains events, consumer restarts and resumes |
| PostgreSQL down | Video lookups fail → segments can't be served | ❌ Core streaming affected (improvement: cache metadata in Redis) |
| FFmpeg crash | New uploads fail | ✅ Live viewing of existing videos continues |
| WebSocket disconnect | That viewer loses chat | ✅ Other viewers unaffected |

### The key isolation mechanism — **Kafka:**
```
Video Delivery ─┐
                 ├── completely independent execution paths
Analytics Write ─┘
```

If the analytics DB is offline and taking 30 seconds per write, **without Kafka** this would block video delivery. **With Kafka**, the video delivery fires an event in <1ms and continues. The analytics write happens in a separate process, can retry, can queue up, can fail and recover — all without touching the streaming path.

---

# BULLET 3 — Deep Dissection

## 3.1 "Low-Latency Video Delivery"

**What "low-latency" means in context:**

For VOD (Video on Demand), latency = time from request to first byte:
- Without Redis: `disk_read_time + network_transfer_time` ≈ 10-20ms + transfer
- With Redis cache hit: `redis_read_time + network_transfer_time` < 1ms + transfer

**The numbers from your load test:**
- Average segment response time: **45ms** (including network transfer of a ~1.3MB file)
- That's blazing fast for a 1.3MB response — essentially wire speed

**For live streaming, latency = delay from broadcast to viewer:**
- 2-second HLS segments + playlist refresh interval = **4-6 second latency** to viewers
- This is the standard "low-latency HLS" range (vs 30+ seconds for regular HLS)
- Ultra-low-latency would use `LL-HLS` or WebRTC (sub-1-second)

**Techniques you used to minimize latency:**
1. **Redis caching** — eliminates disk I/O on every request
2. **`-c:v copy`** in FFmpeg — no re-encoding delay for VOD
3. **`-hls_time 2`** for live — 2-second segments vs 10-second → viewer is always ≤2s from real-time

---

## 3.2 "Real-Time Chat via WebSockets"

**What "real-time" actually means:**

Real-time = the perceived latency is low enough users don't notice delay. In chat:
- Message posted → appears on all screens in < 200ms = real-time
- Message posted → appears after 2-3 seconds = not real-time (feels broken)

**Your implementation achieves this because:**
1. WebSocket = persistent TCP connection = no new connection overhead per message
2. Redis Pub/Sub = sub-millisecond message routing between server instances
3. `broadcast()` iterates all sessions and sends synchronously in the listener callback

**The actual round-trip path:**
```
[User types + hits Enter]
    → ws.send("Hello!")  [WebSocket frame, ~microseconds]
    → ChatWebSocketHandler.handleTextMessage()
    → objectMapper.writeValueAsString()  [~microseconds]
    → redisTemplate.opsForList().leftPush()  [~1ms, persist to history]
    → redisTemplate.convertAndSend("chat", json)  [~0.5ms, publish]
    → RedisMessageListenerContainer receives  [~0.5ms]
    → RedisMessageSubscriber.receiveMessage()  [~microseconds]
    → ChatWebSocketHandler.broadcast()  [for each session: session.sendMessage()]
    → [All viewers receive within ~5ms total]
```

**Why this is technically impressive:** You built a real-time messaging system that's horizontally scalable, persistent (Redis history), and works within a Spring Boot application without a separate signaling server.

---

## 3.3 "Fault-Tolerant Analytics Pipelines"

**The full producer-consumer picture** (the part missing from earlier docs):

### Producer — `PlayerController.java` (the REAL Kafka code):
```java
@Controller
public class PlayerController {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    // KafkaTemplate<K, V>: K = message key type, V = message value type

    private static final String KAFKA_TOPIC = "analytics-events";

    @GetMapping("/player/{videoId}")
    public String showPlayerPage(@PathVariable String videoId, Model model) {
        model.addAttribute("videoId", videoId);
        
        String userId = "user_anonymous";
        String eventMessage = String.format(
            "PLAYBACK_STARTED: User '%s' started watching video '%s'", 
            userId, videoId
        );
        
        System.out.println("Sending Kafka event: " + eventMessage);
        kafkaTemplate.send(KAFKA_TOPIC, eventMessage);
        // kafkaTemplate.send() returns ListenableFuture<SendResult<K,V>>
        // By default, it's async — won't block showPlayerPage()
        
        return "player";
    }
}
```

**What `kafkaTemplate.send()` does internally:**
1. Serializes the message string to bytes
2. Determines the partition (default: round-robin or by key hash)
3. Batches the message in the producer's internal buffer (`batch.size` default 16KB)
4. A background `Sender` thread flushes the batch to the broker
5. The broker acknowledges receipt (based on `acks` setting)

This is why it's "async" — step 3 is immediate; steps 4-5 happen in the background.

### Consumer (Conceptual — separate service):
```java
@KafkaListener(topics = "analytics-events", groupId = "analytics-consumers")
public void consumeAnalyticsEvent(String message, 
                                   @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                                   @Header(KafkaHeaders.OFFSET) long offset) {
    System.out.println("Received from partition " + partition + " at offset " + offset);
    // "PLAYBACK_STARTED: User 'user_anonymous' started watching video 'abc-123'"
    
    // Parse the event
    // Write to analytics store
    analyticsDB.recordPlayback(parseEvent(message));
    
    // Kafka tracks the committed offset — if this crashes and restarts,
    // it resumes from the last committed offset → no events lost
}
```

### What "Fault-Tolerant" Means for Kafka Analytics:

**Scenario 1 — Consumer crashes mid-processing:**
```
Kafka offset 100: "PLAYBACK_STARTED for video X"  ← consumer reads but hasn't committed offset
Consumer crashes.
Consumer restarts → resumes from offset 100 → reprocesses the event
Result: Event processed (at-least-once semantics)
```

**Scenario 2 — Analytics DB is down:**
```
Consumer reads event from Kafka
Tries to write to analytics DB → fails
Consumer does NOT commit offset
Kafka keeps the event available
Consumer retries (with backoff)
When DB recovers → event is processed
Result: No event lost
```

**Scenario 3 — Producer can't reach Kafka broker:**
```
kafkaTemplate.send() → fails (broker unavailable)
The HTTP request continues normally (send() is async, failure caught in callback)
The PLAYBACK_STARTED event is lost
Result: Analytics miss this event (acceptable tradeoff for availability)
// For guaranteed delivery: synchronous send + retry with acks=all
```

---

## 4. The Locust Test Code — What It Actually Proves (`vod_test.py`)

```python
from locust import HttpUser, task, between
import re
import random

# Real video UUIDs from your PostgreSQL database
VIDEO_IDS = [
    "ba266afe-78cd-4176-b41f-184397b4cf50",
    "57827afa-9788-4988-8030-c27728c70ee9",
    "cf1e9ba5-c66d-48a7-a081-a95efb439dcb",
    "504fa2ee-bb69-4756-b334-58cd830b940f",
]

class VODUser(HttpUser):
    wait_time = between(1, 3)  # Each simulated user waits 1-3s between tasks (realistic behavior)

    @task
    def watch_vod_stream(self):
        video_id = random.choice(VIDEO_IDS)  # Random video selection per user
        playlist_url = f"/stream/{video_id}/playlist.m3u8"

        # Step 1: Fetch the playlist
        with self.client.get(
            playlist_url,
            catch_response=True,
            name="/stream/[id]/playlist.m3u8"  # Groups all video IDs under one metric name
        ) as response:
            if response.status_code == 200:
                # Step 2: Parse the playlist to find ALL segment filenames
                ts_files = re.findall(r"^(playlist\d+\.ts)$", response.text, re.MULTILINE)
                # Regex: finds lines like "playlist0.ts", "playlist1.ts", etc.
                
                # Step 3: Request EVERY segment in sequence (simulates a real viewer)
                for ts_file in ts_files:
                    chunk_url = f"/stream/{video_id}/{ts_file}"
                    self.client.get(chunk_url, name="/stream/[id]/[segment].ts")
            else:
                response.failure(f"Failed to get playlist, status code: {response.status_code}")
```

**Why this test is realistic:**
- Real HLS players do exactly this: fetch playlist → parse segment URLs → download sequentially
- Multiple `VODUser` instances run concurrently → simulates actual viewers
- The regex `re.findall(r"^(playlist\d+\.ts)$", ...)` correctly parses the real `.m3u8` format
- `name` parameter groups metrics by logical name, not by unique URL — gives meaningful statistics

**What the test validates:**
1. **Playlist endpoint** works under load
2. **Segment endpoints** handle concurrent reads
3. **Redis cache** warms up as segments are requested (first viewer pays disk I/O; subsequent ones don't)
4. **Zero errors** = no race conditions, no thread-safety bugs in segment serving

---

## 5. The `Helper` Class — Unified Email Resolution

```java
public class Helper {
    public static String getEmailOfLoggedInUser(Authentication authentication) {
        if (authentication instanceof OAuth2AuthenticationToken) {
            var token = (OAuth2AuthenticationToken) authentication;
            var clientId = token.getAuthorizedClientRegistrationId();
            var oauth2User = (OAuth2User) authentication.getPrincipal();
            
            if (clientId.equalsIgnoreCase("google")) {
                return oauth2User.getAttribute("email").toString();
            } else if (clientId.equalsIgnoreCase("github")) {
                // GitHub email can be null if private
                return oauth2User.getAttribute("email") != null 
                    ? oauth2User.getAttribute("email").toString()
                    : oauth2User.getAttribute("login").toString() + "@gmail.com";
            }
        } else {
            // Standard form login: authentication.getName() = email
            return authentication.getName();
        }
    }
}
```

**Why this helper exists:** The same "get the current user's email" logic needs to work for 3 login types. Without `Helper`, this if-else chain would be duplicated in every controller. This is the **DRY principle** (Don't Repeat Yourself) applied.

**This same pattern appears in `UserController.generateStreamKey()`** — the logic there is embedded inline, whereas `Helper.getEmailOfLoggedInUser()` was extracted for reuse. You can point this out as a refactoring opportunity.

---

## 6. The `Providers` Enum — Multi-Provider Auth Design

```java
public enum Providers {
    SELF,       // Email/password registration
    GOOGLE,     // OAuth2 Google
    FACEBOOK,   // Planned (stub in handler)
    TWITTER,    // Planned (stub)
    LINKEDIN,   // Planned (empty branch in handler)
    GITHUB      // OAuth2 GitHub
}
```

**Why `EnumType.STRING` instead of `EnumType.ORDINAL`?**

`@Enumerated(value = EnumType.STRING)` stores the enum as `"GOOGLE"`, `"SELF"`, etc.

`EnumType.ORDINAL` (default) stores as `0, 1, 2...` — **dangerous!** If you ever add a new enum value between existing ones (e.g., add `APPLE` between `FACEBOOK` and `TWITTER`), all ordinals shift and your data is corrupted. String is safe — adding enum values never affects existing data.

---

## 7. The `@RestController` vs `@Controller` Distinction in Action

StreamLite has **both** types:

| Controller | Annotation | Why |
|---|---|---|
| `StreamingController` | `@Controller` | Methods return `ResponseEntity<byte[]>` directly — no view |
| `VideoController` | `@RestController` | Full REST API — all methods return JSON |
| `UserController` | `@Controller` + `@ResponseBody` on specific methods | Mix: some methods return views, one is API |
| `PageController` | `@Controller` | Only Thymeleaf view names |
| `PlayerController` | `@Controller` | Returns view name + fires Kafka event |
| `AuthController` | `@Controller` + `@ResponseBody` | Session auth API endpoint |

**`@RestController`** = `@Controller` + `@ResponseBody` on all methods.

**`@ResponseBody`** tells Spring: don't look for a view template, write the return value directly to the HTTP response body using `HttpMessageConverter` (JSON for objects, raw bytes for `byte[]`).

---

## 8. Spring Boot DevTools — Hot Reload

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-devtools</artifactId>
    <scope>runtime</scope>
    <optional>true</optional>
</dependency>
```

**What DevTools does:**
- Monitors classpath for file changes
- Automatically restarts the Spring context (not the full JVM) when Java files change
- Restarts take ~1-2 seconds instead of full JVM startup (~10 seconds)
- Thymeleaf templates reload without restart
- `optional=true` means it's excluded from the production JAR

---

## 9. `application.properties` — Credentials Warning (What to Say in Interview)

```properties
spring.datasource.password=la@150903
spring.redis.password=la@150903
spring.security.oauth2.client.registration.google.client-secret=GOCSPX-...
spring.security.oauth2.client.registration.github.client-secret=616a3ef9...
```

**⚠️ This is a dev config. In production:**
- Credentials go into environment variables: `${DB_PASSWORD}`
- Or Spring Cloud Config Server / HashiCorp Vault / AWS Secrets Manager
- OAuth2 client secrets are rotated regularly
- This file would be in `.gitignore` — production uses sealed secrets

**How to say this in interview:**
> "For this project the credentials are in `application.properties` for simplicity, but in a production deployment I'd externalize them using environment variables or a secrets manager like HashiCorp Vault, and the `application.properties` would be excluded from version control."

---

## 10. The `Message` & Flash Notification System

```java
// Message.java
@Builder
public class Message {
    private String content;
    private MessageType type;  // green, red, yellow, blue
}

// Used in PageController:
Message message = Message.builder()
    .content("Registration Successful")
    .type(MessageType.green)
    .build();
session.setAttribute("message", message);
return "redirect:/login";
```

This is the **Post-Redirect-Get (PRG) pattern** — after a form POST (register), redirect to GET (login page). The flash message stored in the session is displayed once on the login page, then cleared.

Without PRG: refreshing the browser after registration would re-submit the form.

---

## 11. How to Answer "What Would You Improve?"

**This is the most important question. Here's a precise answer:**

> "There are a few things I'd change for a production system:
>
> 1. **Async video processing:** `ProcessBuilder.waitFor()` blocks the HTTP thread synchronously. For production, I'd fire a Kafka event with the uploaded file path, have a separate transcoding worker pick it up, and notify the user via WebSocket when processing completes.
>
> 2. **External video storage:** Currently videos are stored on local disk. For scale, I'd move to object storage (S3 or MinIO) and have Nginx generate pre-signed URLs or serve from a CDN.
>
> 3. **Proper security:** CSRF is globally disabled — I'd re-enable it with the proper SameSite cookie strategy. The OAuth2 client secrets are in `application.properties` — I'd move them to HashiCorp Vault.
>
> 4. **Database migrations:** `ddl-auto=update` is risky in production. I'd use Flyway for versioned, auditable schema changes.
>
> 5. **Analytics consumer:** The Kafka consumer side is designed but not running as a full separate service. I'd build a Spring Batch or separate microservice that consumes `analytics-events` and writes to a time-series store or data warehouse.
>
> 6. **Metrics enrichment:** Add custom Micrometer counters for cache hit/miss ratio, Kafka events sent/failed, and active WebSocket connection count."

---

## 12. Key Numbers to Memorize

| Stat | Value | Context |
|---|---|---|
| Spring Boot version | **3.3.1** | Latest major (Jakarta EE namespace) |
| Java version | **17 LTS** | Modern, long-term-support |
| Server port | **8000** | Not default 8080 |
| Max upload size | **50MB** | `spring.servlet.multipart.max-file-size` |
| HLS segment duration (VOD) | **10 seconds** | `segment_video.bat` |
| HLS segment duration (Live) | **2 seconds** | `start_live.bat` |
| Redis segment TTL | **1 hour** | `TimeUnit.HOURS` |
| Chat history kept | **Last 1000 messages** | `LTRIM 0 999` |
| Chat history shown on join | **Last 50 messages** | `LRANGE 0 49` |
| Load test peak RPS | **488** | Locust, segment endpoint |
| Load test error rate | **0%** | Zero failures |
| Average response time (segments) | **45ms** | Including ~1.3MB transfer |
| Theoretical max throughput | **~5.14 Gbps** | Hit NIC ceiling |
| Kafka topic name | `"analytics-events"` | `PlayerController.KAFKA_TOPIC` |
| Redis Pub/Sub channel | `"chat"` | `ChatWebSocketHandler` |
| Redis chat history key | `"chat:history"` | Redis List |
| PostgreSQL database | `StreamLite` | Port 5432 |
| Redis port | `6379` | Default |
| Kafka broker | `localhost:9092` | Default |
| BCrypt work factor | **10** (default) | `new BCryptPasswordEncoder()` |
| WebSocket endpoint | `/chat` | `WebSocketConfig` |
| Stream key format | **UUID v4** | `UUID.randomUUID().toString()` |
