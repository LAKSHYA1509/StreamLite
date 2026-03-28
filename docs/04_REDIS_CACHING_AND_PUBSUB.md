# 🧠 Redis — Caching & Pub/Sub Deep Dive

> Redis is used in TWO distinct ways in StreamLite: as a cache for HLS video segments, and as a Pub/Sub message broker for the live chat. This document covers both.

---

## 1. What Is Redis?

**Redis** (Remote Dictionary Server) is an **in-memory** key-value data store. Unlike disk-based databases (PostgreSQL), Redis stores data in RAM, giving it:
- **Sub-millisecond** read/write latency
- **High throughput** (millions of operations per second)
- **Persistence options** (RDB snapshots, AOF logging)
- **Data structures**: Strings, Lists, Sets, Sorted Sets, Hashes, Streams, Pub/Sub channels

---

## 2. How Redis Is Configured in StreamLite

### `application.properties`
```properties
spring.redis.host=localhost
spring.redis.port=6379
spring.redis.password=la@150903
```

### `RedisConfig.java` — Complete Analysis

```java
@Configuration
public class RedisConfig {

    // ========================================================
    // Bean 1: RedisTemplate<String, byte[]>
    // Used for: Video segment caching
    // Key type: String (e.g., "videoId:playlist0.ts")
    // Value type: byte[] (raw binary video data)
    // ========================================================
    @Bean
    public RedisTemplate<String, byte[]> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, byte[]> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        return template;
    }

    // ========================================================
    // Bean 2: MessageListenerAdapter
    // Wraps RedisMessageSubscriber to adapt it to Spring's
    // MessageListener interface. 
    // The "receiveMessage" string tells it which method to call.
    // ========================================================
    @Bean
    MessageListenerAdapter messageListener(RedisMessageSubscriber subscriber) {
        return new MessageListenerAdapter(subscriber, "receiveMessage");
    }

    // ========================================================
    // Bean 3: RedisMessageListenerContainer
    // This is the actual Pub/Sub subscriber.
    // It runs in a background thread, listening to the "chat"
    // channel, and calls messageListener when a message arrives.
    // ========================================================
    @Bean
    RedisMessageListenerContainer redisContainer(
            RedisConnectionFactory connectionFactory, 
            MessageListenerAdapter messageListener) {
        
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        // Subscribe to the "chat" channel specifically
        container.addMessageListener(messageListener, new ChannelTopic("chat"));
        return container;
    }
}
```

**Why two different Redis templates?**
- `RedisTemplate<String, byte[]>` — for video segments (binary data, no serialization)
- `StringRedisTemplate` — for chat messages (text JSON, auto-serialized as UTF-8 strings)

These are two separate beans because they handle different data types and serialization strategies.

---

## 3. Redis as a Video Segment Cache

### The Caching Flow

```
GET /stream/{id}/playlist0.ts
        │
        ├─ redisTemplate.opsForValue().get("videoId:playlist0.ts")
        │       ├─ CACHE HIT → return bytes immediately (< 1ms)
        │       │
        │       └─ CACHE MISS:
        │               ├─ Read file from disk (5-15ms)
        │               ├─ redisTemplate.opsForValue().set(
        │               │       "videoId:playlist0.ts",
        │               │       videoBytes,
        │               │       1,                          ← TTL value
        │               │       TimeUnit.HOURS              ← TTL unit
        │               │   )
        │               └─ Return bytes (10-20ms total)
```

### Cache Key Design

```java
String cacheKey = id + ":" + fileName;
// Example: "d3b4c5f0-abc1-4def-8901-234567890abc:playlist3.ts"
```

**Why this format?**
- `id` (UUID) = the video identifier — ensures different videos don't collide
- `fileName` = the specific segment — ensures different segments have unique keys
- Delimiter `:` is a Redis convention for namespacing keys

### TTL (Time-to-Live)

```java
redisTemplate.opsForValue().set(cacheKey, videoBytes, 1, TimeUnit.HOURS);
```

**1 hour TTL** means the segment is auto-deleted after 1 hour. This prevents Redis from running out of memory for infrequently watched videos.

**Memory consideration:** A typical 10-second HLS segment for a 720p video is ~1-5 MB. For a video with 100 segments, that's 100-500 MB per video in Redis. TTL is critical for memory management.

### `opsForValue()` — Redis String Operations

`opsForValue()` maps to Redis's **String data type** (despite the name, Redis Strings are binary-safe, can store any binary data including video bytes).

| Operation | Method | Redis Command |
|---|---|---|
| Set value | `set(key, value)` | `SET key value` |
| Set with TTL | `set(key, value, timeout, unit)` | `SET key value EX seconds` |
| Get value | `get(key)` | `GET key` |
| Delete | `delete(key)` | `DEL key` |

---

## 4. Redis Pub/Sub for Live Chat

### The Architecture

```
Viewer A (WebSocket) → Server Instance 1
                              │
                              │ handleTextMessage()
                              ↓
                    redisTemplate.convertAndSend("chat", jsonMessage)
                              │
                              ▼
                         [Redis "chat" channel]
                              │
                    ┌─────────┴─────────┐
                    ▼                   ▼
              Server Instance 1   Server Instance 2
                    │                   │
             RedisMessageSubscriber     │
                    │                   │
             chatHandler.broadcast()   chatHandler.broadcast()
                    │                   │
              ALL viewers on       ALL viewers on
              Server 1             Server 2
              (including A)
```

### ChatWebSocketHandler.java — Complete Analysis

```java
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    // Thread-safe list — multiple threads (one per WebSocket) can add/remove safely
    private final List<WebSocketSession> sessions = new CopyOnWriteArrayList<>();
    
    private final ObjectMapper objectMapper = new ObjectMapper();  // Jackson JSON
    
    // 6 predefined chat colors — assigned randomly to each user
    private final String[] colors = {"#FF5733","#33FF57","#3357FF","#FF33A1","#A133FF","#33FFA1"};
    
    private static final String CHAT_HISTORY_KEY = "chat:history";
    
    @Autowired
    private StringRedisTemplate redisTemplate;  // For chat (String-based, not byte[])

    // ═══════════════════════════════════════════════════
    // Called when a new WebSocket connection is established
    // ═══════════════════════════════════════════════════
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        
        // Assign random identity
        String username = "user_" + new Random().nextInt(1000);
        String color = colors[new Random().nextInt(colors.length)];
        session.getAttributes().put("username", username);
        session.getAttributes().put("color", color);

        // Load chat history (last 50 messages) from Redis
        List<String> chatHistory = redisTemplate.opsForList().range(CHAT_HISTORY_KEY, 0, 49);
        if (chatHistory != null) {
            // Redis list is newest-first (we use leftPush), so reverse for chronological order
            for (int i = chatHistory.size() - 1; i >= 0; i--) {
                session.sendMessage(new TextMessage(chatHistory.get(i)));
            }
        }
    }

    // ═══════════════════════════════════════════════════
    // Called when a message arrives from a WebSocket client
    // ═══════════════════════════════════════════════════
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message)
            throws JsonProcessingException {
        
        // 1. Build structured message object
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setContent(message.getPayload());    // The actual text typed
        chatMessage.setUsername((String) session.getAttributes().get("username"));
        chatMessage.setColor((String) session.getAttributes().get("color"));

        // 2. Serialize to JSON: {"username":"user_42","color":"#FF5733","content":"Hello!"}
        String jsonMessage = objectMapper.writeValueAsString(chatMessage);

        // 3. Persist message to Redis List (for history)
        redisTemplate.opsForList().leftPush(CHAT_HISTORY_KEY, jsonMessage);
        redisTemplate.opsForList().trim(CHAT_HISTORY_KEY, 0, 999); // Keep last 1000 msgs

        // 4. Publish to "chat" channel — ALL subscribers get this
        redisTemplate.convertAndSend("chat", jsonMessage);
        // This triggers RedisMessageSubscriber.receiveMessage() → broadcast()
    }

    // ═══════════════════════════════════════════════════
    // Called by RedisMessageSubscriber — broadcasts to all WebSocket sessions
    // ═══════════════════════════════════════════════════
    public void broadcast(TextMessage message) throws IOException {
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                session.sendMessage(message);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }
}
```

### RedisMessageSubscriber.java

```java
@Service
public class RedisMessageSubscriber {

    @Autowired
    private ChatWebSocketHandler chatWebSocketHandler;

    // This method is called by RedisMessageListenerContainer
    // when a message arrives on the "chat" channel
    public void receiveMessage(String message) {
        try {
            // Bridge: Redis → WebSocket
            chatWebSocketHandler.broadcast(new TextMessage(message));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
```

### Why `CopyOnWriteArrayList` for sessions?

WebSocket connections are managed by **multiple threads** concurrently:
- Thread A: new user connects → `sessions.add(session)`
- Thread B: user disconnects → `sessions.remove(session)`
- Thread C: broadcasting → iterating `sessions`

A regular `ArrayList` would throw `ConcurrentModificationException`. `CopyOnWriteArrayList` creates a new copy of the list on writes, so reads (iterating during broadcast) are always safe. It's slightly slower for writes but fine for a chat application where reads vastly outnumber writes.

---

## 5. Redis Data Structures Used

| Feature | Redis Type | Key | Operations Used |
|---|---|---|---|
| Video segment cache | String (binary) | `"videoId:segment.ts"` | GET, SET with TTL |
| Chat history | List | `"chat:history"` | LPUSH, LRANGE, LTRIM |
| Chat broadcast | Pub/Sub | `"chat"` channel | PUBLISH, SUBSCRIBE |

### Chat History with `opsForList()`

```java
// Add new message to the LEFT (newest first)
redisTemplate.opsForList().leftPush("chat:history", jsonMessage);

// Keep only last 1000 messages
redisTemplate.opsForList().trim("chat:history", 0, 999);

// Get last 50 messages (indices 0-49, newest first)
List<String> history = redisTemplate.opsForList().range("chat:history", 0, 49);
```

**`LPUSH` + reverse on read:** Pushing to the left means index 0 is always the newest message. When sending history to a new client, we iterate in reverse (`chatHistory.size()-1` to `0`) to present messages in chronological order.

---

## 6. Interview Questions on Redis — Prepared Answers

**Q: What is the difference between Redis and a traditional database?**
> Redis is an **in-memory** data store — data lives in RAM. Traditional databases (PostgreSQL) store data on disk. Redis is 100-1000x faster for reads/writes but limited by available RAM and is (by default) not durable across restarts. Redis is used as a **cache layer** on top of PostgreSQL, not as a replacement.

**Q: What is Redis Pub/Sub?**
> Pub/Sub is a Redis messaging pattern where publishers send messages to channels, and subscribers receive them. It's fire-and-forget — if no subscriber is listening when a message is published, the message is lost. In StreamLite, the WebSocket handler publishes chat messages to `"chat"`, and the `RedisMessageListenerContainer` subscribes and calls `receiveMessage()`.

**Q: Why use Redis Pub/Sub instead of broadcasting directly in the WebSocket handler?**
> If you have multiple instances of the application (horizontal scaling), each instance only knows about its own WebSocket connections. Without Pub/Sub, a message received by Instance 1 would only be broadcast to viewers connected to Instance 1. With Redis Pub/Sub, all instances subscribe to the same channel, so the message is broadcast to ALL viewers regardless of which server they're connected to.

**Q: What is the difference between `RedisTemplate` and `StringRedisTemplate`?**
> `StringRedisTemplate` is a pre-configured `RedisTemplate<String, String>` that uses `StringRedisSerializer` for both keys and values — it's for text data. `RedisTemplate<String, byte[]>` uses raw byte serialization for values — used for binary data like video segments.

**Q: What is TTL in Redis and why did you set it to 1 hour for segments?**
> TTL (Time-to-Live) is an expiry period after which Redis automatically deletes the key. 1 hour was chosen because: (1) Redis memory is limited, (2) Frequently watched segments stay warm in cache, (3) Old/rarely accessed segments are eventually evicted to free memory.

**Q: What happens when Redis runs out of memory?**
> Redis uses an `eviction policy`. Common policies include:
> - `noeviction`: Reject new writes (default — dangerous)
> - `allkeys-lru`: Evict least recently used keys (best for caching)
> - `volatile-lru`: Evict LRU keys that have TTL (what we'd want)
> For a production system, we'd configure `maxmemory-policy volatile-lru` and set a `maxmemory` limit.

**Q: How would you handle Redis being down?**
> The cache layer should be optional (fail-open). The streaming controller should catch Redis connection exceptions and fall back to reading from disk. Redis unavailability should degrade performance, not cause 500 errors. A circuit breaker pattern (using Resilience4j) would be appropriate.
