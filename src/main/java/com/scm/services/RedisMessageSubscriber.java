package com.scm.services;

import com.scm.config.ChatWebSocketHandler;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
@Service
public class RedisMessageSubscriber {
    public void receiveMessage(String message) {
        try {
            ChatWebSocketHandler.broadcast(new TextMessage(message));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}