package com.scm.services;

import com.scm.config.ChatWebSocketHandler;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.beans.factory.annotation.Autowired;
@Service
public class RedisMessageSubscriber {

    @Autowired
    private ChatWebSocketHandler chatWebSocketHandler;
    public void receiveMessage(String message) {
        try {
            chatWebSocketHandler.broadcast(new TextMessage(message));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}