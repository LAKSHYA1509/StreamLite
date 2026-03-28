# 💬 WebSocket — Real-Time Live Chat Deep Dive

> WebSocket is used for the live chat feature that accompanies live streams. This is a crucial system design topic in interviews.

---

## 1. What Is WebSocket?

**WebSocket** is a communication protocol providing **full-duplex, bidirectional** communication over a single TCP connection. Unlike HTTP:

| Feature | HTTP | WebSocket |
|---|---|---|
| Direction | Half-duplex (request → response) | Full-duplex (both ways simultaneously) |
| Connection | Stateless (new connection per request) | Persistent (one connection, stays open) |
| Overhead | High (headers on every request) | Low (minimal framing after handshake) |
| Server Push | No (client must ask every time) | Yes (server can push anytime) |
| Latency | Higher (connection setup per request) | Lower (connection already open) |

---

## 2. WebSocket Handshake — Step by Step

WebSocket starts with an HTTP upgrade handshake:

```
Client → Server:
GET /chat HTTP/1.1
Host: localhost:8000
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==
Sec-WebSocket-Version: 13

Server → Client:
HTTP/1.1 101 Switching Protocols
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
```

After this, the TCP connection stays open and both sides can send **WebSocket frames** freely.

**`Sec-WebSocket-Key`** — The client sends a random 16-byte base64 value. The server concatenates it with the "magic string" `258EAFA5-E914-47DA-95CA-C5AB0DC85B11`, SHA-1 hashes it, and returns the base64 result. This proves the server supports WebSocket (not just any HTTP server).

---

## 3. WebSocket Configuration in StreamLite

### `WebSocketConfig.java`

```java
@Configuration
@EnableWebSocket        // Activates WebSocket support in Spring
public class WebSocketConfig implements WebSocketConfigurer {

    @Autowired
    private ChatWebSocketHandler chatWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, "/chat")
                .setAllowedOrigins("*");    // ⚠️ Allow all origins (dev setting)
                                             // Production: specific domain only
    }
}
```

**What this registers:**
- WebSocket endpoint: `ws://localhost:8000/chat`
- Handler: `ChatWebSocketHandler` (singleton, Spring-managed)
- CORS: All origins allowed (`*`)

**Note on STOMP:** This uses **native Spring WebSocket** (not STOMP over SockJS). STOMP is a sub-protocol that adds message routing, destinations, subscriptions (like a lightweight message broker). Native WebSocket is simpler but gives more control.

---

## 4. ChatWebSocketHandler — Line-by-Line Analysis

```java
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {
    // Spring manages this as a SINGLETON — one instance for ALL connections
    
    // Thread-safe session list — multiple threads modify this concurrently
    private final List<WebSocketSession> sessions = new CopyOnWriteArrayList<>();
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // Colors assigned randomly to users for visual distinction in chat
    private final String[] colors = {
        "#FF5733", "#33FF57", "#3357FF", "#FF33A1", "#A133FF", "#33FFA1"
    };
    
    private static final String CHAT_HISTORY_KEY = "chat:history";
    
    @Autowired
    private StringRedisTemplate redisTemplate;
```

### Method: `afterConnectionEstablished` (new user connects)

```java
@Override
public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    // 1. Register this session globally
    sessions.add(session);
    
    // 2. Assign random anonymous identity
    String username = "user_" + new Random().nextInt(1000);
    String color = colors[new Random().nextInt(colors.length)];
    
    // 3. Store identity in session attributes (scoped to this connection)
    session.getAttributes().put("username", username);
    session.getAttributes().put("color", color);
    
    System.out.println("User '" + username + "' connected. Session ID: " + session.getId());
    
    // 4. Send chat history (last 50 messages) to the new user
    List<String> chatHistory = redisTemplate.opsForList().range(CHAT_HISTORY_KEY, 0, 49);
    if (chatHistory != null) {
        // History is stored newest-first (LPUSH); reverse for chronological order
        for (int i = chatHistory.size() - 1; i >= 0; i--) {
            session.sendMessage(new TextMessage(chatHistory.get(i)));
        }
    }
}
```

**Why load history from Redis?** When a new viewer joins a live stream mid-way, they need context — what was already said. Redis stores up to 1000 messages. The new user gets the last 50 in chronological order.

### Method: `handleTextMessage` (user sends a message)

```java
@Override
protected void handleTextMessage(WebSocketSession session, TextMessage message)
        throws JsonProcessingException {
    
    // 1. Build a structured message with metadata
    ChatMessage chatMessage = new ChatMessage();
    chatMessage.setContent(message.getPayload());  // Raw text from client
    chatMessage.setUsername((String) session.getAttributes().get("username"));
    chatMessage.setColor((String) session.getAttributes().get("color"));
    
    // 2. Serialize to JSON
    String jsonMessage = objectMapper.writeValueAsString(chatMessage);
    // → {"username":"user_42","color":"#FF5733","content":"Hello everyone!"}
    
    // 3. Persist to Redis list (chat history)
    redisTemplate.opsForList().leftPush(CHAT_HISTORY_KEY, jsonMessage);
    redisTemplate.opsForList().trim(CHAT_HISTORY_KEY, 0, 999);  // Keep last 1000
    
    // 4. PUBLISH to Redis "chat" channel
    // This triggers RedisMessageSubscriber.receiveMessage() on ALL server instances
    redisTemplate.convertAndSend("chat", jsonMessage);
}
```

### Method: `broadcast` (called by Redis subscriber)

```java
// Called by RedisMessageSubscriber when a message arrives from Redis
public void broadcast(TextMessage message) throws IOException {
    for (WebSocketSession session : sessions) {
        if (session.isOpen()) {   // Check — session might have closed between iterations
            session.sendMessage(message);
        }
    }
}
```

**Thread safety of `sessions`:**

Multiple threads are running concurrently:
- Thread 1 (WebSocket I/O): `sessions.add()` when new user connects
- Thread 2 (WebSocket I/O): `sessions.remove()` when user disconnects
- Thread 3 (Redis listener): `broadcast()` iterates `sessions`

`CopyOnWriteArrayList` solves this: writes create a new copy of the array, reads use the current snapshot. The broadcasting thread sees a consistent view even if another thread is modifying the list.

---

## 5. The Complete Chat Message Flow

```
User types "Hello!" in browser, presses Enter
    │
    │ (WebSocket frame sent over persistent TCP connection)
    ▼
ChatWebSocketHandler.handleTextMessage(session, "Hello!")
    │
    ├─ Build ChatMessage: {username: "user_42", color: "#FF5733", content: "Hello!"}
    ├─ Serialize: '{"username":"user_42","color":"#FF5733","content":"Hello!"}'
    ├─ Redis LPUSH "chat:history" → persist to history
    └─ Redis PUBLISH "chat" → broadcast to all subscribers
            │
            ▼
    RedisMessageListenerContainer (background thread, listening to "chat")
            │
            ▼
    MessageListenerAdapter.onMessage() → calls subscriber.receiveMessage()
            │
            ▼
    RedisMessageSubscriber.receiveMessage(jsonMessage)
            │
            ▼
    ChatWebSocketHandler.broadcast(new TextMessage(jsonMessage))
            │
            ├─ session1.sendMessage() → Viewer 1 sees "Hello!"
            ├─ session2.sendMessage() → Viewer 2 sees "Hello!"
            └─ session3.sendMessage() → Viewer 3 sees "Hello!"
```

**Total round-trip time:** < 5ms on a local network.

---

## 6. The ChatMessage POJO

```java
public class ChatMessage {
    private String username;   // "user_42"
    private String color;      // "#FF5733" (random color for this user)
    private String content;    // The actual message text
    
    // Standard getters/setters
}
```

**Why a separate POJO?** The raw WebSocket message from the client is just plain text (the typed message). The server enriches it with `username` and `color` metadata before storing/broadcasting. The POJO enables clean Jackson serialization.

---

## 7. Frontend WebSocket Connection

The browser connects to the WebSocket in JavaScript:

```javascript
const ws = new WebSocket("ws://localhost:8000/chat");

// When connected
ws.onopen = () => {
    console.log("Connected to chat");
};

// When a message arrives from server
ws.onmessage = (event) => {
    const msg = JSON.parse(event.data);
    // msg = { username: "user_42", color: "#FF5733", content: "Hello!" }
    displayMessage(msg);
};

// Send a message
function sendMessage(text) {
    ws.send(text);  // Just plain text — server adds the metadata
}
```

---

## 8. Live Player Page

```java
@GetMapping("/live")
public String livePlayerPage() {
    return "live_player";  // Renders templates/live_player.html
}
```

The `live_player.html` contains:
1. A video player (HLS.js) for the live stream
2. A chat panel connected via WebSocket to `/chat`
3. The stream key display for creator's reference

---

## 9. WebSocket vs Server-Sent Events vs Long Polling

**Why WebSocket instead of alternatives?**

| Technique | Direction | Connection | Use Case |
|---|---|---|---|
| **Long Polling** | Server → Client (simulated) | New request per message | Legacy browsers |
| **Server-Sent Events (SSE)** | Server → Client only | Persistent, one-way | Notifications, feeds |
| **WebSocket** | Full-duplex | Persistent, both ways | **Chat, gaming, real-time collab** |
| **WebRTC** | P2P | Direct peer connection | Video/audio calls |

For live chat, WebSocket is the only appropriate choice — messages flow both from the viewer (they type) and to the viewer (others' messages appear).

---

## 10. Interview Questions on WebSocket — Prepared Answers

**Q: What is WebSocket and how is it different from HTTP?**
> WebSocket provides a persistent, full-duplex communication channel over TCP. Unlike HTTP where the client must initiate every exchange, WebSocket allows both the client and server to send data at any time. After the initial HTTP upgrade handshake (HTTP 101 Switching Protocols), the connection stays open until explicitly closed.

**Q: How does the WebSocket handshake work?**
> The client sends an HTTP GET request with `Upgrade: websocket` and `Connection: Upgrade` headers plus a base64 challenge key. The server responds with `101 Switching Protocols` and the hashed key. After this, the TCP connection is no longer HTTP — it's WebSocket.

**Q: What is `TextWebSocketHandler` and why extend it?**
> `TextWebSocketHandler` is Spring's base class for handling text-based WebSocket messages. Extending it gives you `handleTextMessage()` (for received messages), `afterConnectionEstablished()` (new connection), and `afterConnectionClosed()` (disconnection) hooks. For binary data (like video), you'd extend `BinaryWebSocketHandler`.

**Q: Why is CopyOnWriteArrayList better than ArrayList here?**
> Multiple threads access the session list concurrently — WebSocket I/O threads add/remove sessions, and the Redis listener thread iterates to broadcast. `CopyOnWriteArrayList` provides thread safety by creating a new copy of the underlying array on every write. The read (iteration during broadcast) uses the snapshot as of when iteration began — no `ConcurrentModificationException`.

**Q: How would you scale the live chat to multiple servers?**
> With Redis Pub/Sub — all server instances subscribe to the same `"chat"` Redis channel. When any server receives a WebSocket message, it publishes to Redis. Redis delivers it to ALL subscribing server instances, each of which broadcasts to its local WebSocket sessions. This is exactly how it's implemented in StreamLite.

**Q: What is STOMP and why didn't you use it?**
> STOMP (Simple Text Oriented Messaging Protocol) is a sub-protocol over WebSocket providing message routing to "destinations" (like `"/topic/chat"`), subscriptions, and connection management. Spring also provides STOMP with SockJS fallback. I used native WebSocket because: (1) The chat use case doesn't need routing (everyone sees all messages), (2) Native WebSocket gives finer control, (3) Simpler setup. STOMP would be appropriate if we needed private messaging or different chat rooms.

**Q: What happens if a user's WebSocket connection drops?**
> `afterConnectionClosed()` is called, removing the session from the list. The user won't receive messages. The client JavaScript would need to implement **auto-reconnect** with exponential backoff:
> ```javascript
> ws.onclose = () => setTimeout(connect, 3000); // Reconnect after 3 seconds
> ```
> On reconnect, they'd receive the last 50 messages from Redis history to recover context.
