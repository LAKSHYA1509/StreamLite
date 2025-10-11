package com.scm.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component // Make this a Spring Component to allow dependency injection
public class ChatWebSocketHandler extends TextWebSocketHandler {

    // A thread-safe list to hold all active sessions on THIS server instance
    private static final List<WebSocketSession> sessions = new CopyOnWriteArrayList<>();

    

    // Use a static field to allow injection
    private static StringRedisTemplate staticRedisTemplate;

    @Autowired
    public void setStaticRedisTemplate(StringRedisTemplate redisTemplate) {
        ChatWebSocketHandler.staticRedisTemplate = redisTemplate;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        System.out.println("New WebSocket connection: " + session.getId() + " on this server.");
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // Instead of broadcasting, PUBLISH the message to the Redis "chat" channel
        System.out.println("Publishing message to Redis: " + message.getPayload());
        staticRedisTemplate.convertAndSend("chat", message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, org.springframework.web.socket.CloseStatus status) {
        sessions.remove(session);
        System.out.println("WebSocket connection closed: " + session.getId());
    }

    // Static method for the subscriber to broadcast messages to local clients
    public static void broadcast(TextMessage message) throws IOException {
        for (WebSocketSession webSocketSession : sessions) {
            if (webSocketSession.isOpen()) {
                webSocketSession.sendMessage(message);
            }
        }
    }
}