package com.scm.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scm.entities.ChatMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    // Use instance fields now, not static ones. Spring will manage this as a singleton.
    private final List<WebSocketSession> sessions = new CopyOnWriteArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String[] colors = {"#FF5733", "#33FF57", "#3357FF", "#FF33A1", "#A133FF", "#33FFA1"};
    private static final String CHAT_HISTORY_KEY = "chat:history";

    // Inject directly into an instance field.
    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);

        String username = "user_" + new Random().nextInt(1000);
        String color = colors[new Random().nextInt(colors.length)];
        session.getAttributes().put("username", username);
        session.getAttributes().put("color", color);

        System.out.println("User '" + username + "' connected. Session ID: " + session.getId());

        // Load and send chat history to the newly connected client
        List<String> chatHistory = redisTemplate.opsForList().range(CHAT_HISTORY_KEY, 0, 49);
        if (chatHistory != null) {
            // NOTE: History is stored newest-first, so send it in reverse for correct order.
            for (int i = chatHistory.size() - 1; i >= 0; i--) {
                session.sendMessage(new TextMessage(chatHistory.get(i)));
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws JsonProcessingException {
        // --- This is the complete and correct logic ---
        // 1. Create a ChatMessage object with metadata
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setContent(message.getPayload());
        chatMessage.setUsername((String) session.getAttributes().get("username"));
        chatMessage.setColor((String) session.getAttributes().get("color"));

        // 2. Convert the object to a JSON string
        String jsonMessage = objectMapper.writeValueAsString(chatMessage);

        // 3. Save the structured JSON message to Redis history
        redisTemplate.opsForList().leftPush(CHAT_HISTORY_KEY, jsonMessage);
        redisTemplate.opsForList().trim(CHAT_HISTORY_KEY, 0, 999); // Keep last 1000 messages

        // 4. Publish the JSON message to all subscribers (including this server)
        redisTemplate.convertAndSend("chat", jsonMessage);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        System.out.println("WebSocket connection closed: " + session.getId() + " with status: " + status);
    }

    // This method is called by the RedisMessageSubscriber
    public void broadcast(TextMessage message) throws IOException {
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                session.sendMessage(message);
            }
        }
    }
}