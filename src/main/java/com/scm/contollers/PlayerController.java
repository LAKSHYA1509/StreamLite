package com.scm.contollers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate; // Import KafkaTemplate
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.scm.entities.Video;
import com.scm.repsitories.VideoRepo;

import java.util.Optional;

@Controller
public class PlayerController {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate; // Inject the KafkaTemplate

    @Autowired
    private VideoRepo videoRepo; // Inject VideoRepo for video validation

    private static final String KAFKA_TOPIC = "analytics-events";

    @GetMapping("/player/{videoId}")
    public String showPlayerPage(@PathVariable String videoId, Model model) {
        // Check if video exists in database
        Optional<Video> videoOpt = videoRepo.findById(videoId);
        if (videoOpt.isEmpty()) {
            model.addAttribute("error", "Video not found");
            model.addAttribute("message", "The requested video does not exist or has been removed.");
            return "error";
        }

        Video video = videoOpt.get();

        // Check if video has HLS path (i.e., has been processed)
        if (video.getHlsPath() == null || video.getHlsPath().isEmpty()) {
            model.addAttribute("error", "Video not processed");
            model.addAttribute("message", "This video is still being processed. Please try again later.");
            return "error";
        }

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