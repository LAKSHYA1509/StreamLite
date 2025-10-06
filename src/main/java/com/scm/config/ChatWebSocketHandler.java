package com.scm.config;

import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.UUID;

public class ChatWebSocketHandler extends TextWebSocketHandler {

    // Map to store session + associated userId
    private static final Map<WebSocketSession, String> sessionUserMap = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String userId = "user_" + UUID.randomUUID().toString().substring(0, 8);
        sessionUserMap.put(session, userId);
        System.out.println("New WebSocket connection established: " + session.getId() + " as " + userId);

        // Notify everyone that a new user joined
        broadcastMessage("🔵 " + userId + " joined the chat!");
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String userId = sessionUserMap.get(session);
        String payload = message.getPayload();
        String formattedMessage = userId + ": " + payload;
        System.out.println("Message from " + userId + ": " + payload);
        broadcastMessage(formattedMessage);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, org.springframework.web.socket.CloseStatus status) throws Exception {
        String userId = sessionUserMap.remove(session);
        System.out.println("WebSocket connection closed: " + session.getId() + " (" + userId + ")");
        broadcastMessage("🔴 " + userId + " left the chat.");
    }

    private void broadcastMessage(String message) {
        for (WebSocketSession s : sessionUserMap.keySet()) {
            try {
                s.sendMessage(new TextMessage(message));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
