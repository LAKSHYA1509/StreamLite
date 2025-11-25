package com.scm.contollers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate; // Import KafkaTemplate
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class PlayerController {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate; // Inject the KafkaTemplate

    private static final String KAFKA_TOPIC = "analytics-events";

    @GetMapping("/player/{videoId}")
    public String showPlayerPage(@PathVariable String videoId, Model model) {
        model.addAttribute("videoId", videoId);
        
        // --- NEW: Send an event to Kafka ---
        // In a real app, you'd get the real user ID
        String userId = "user_anonymous"; 
        String eventMessage = String.format("PLAYBACK_STARTED: User '%s' started watching video '%s'", userId, videoId);
        
        System.out.println("Sending Kafka event: " + eventMessage);
        kafkaTemplate.send(KAFKA_TOPIC, eventMessage);
        
        return "player";
    }
}