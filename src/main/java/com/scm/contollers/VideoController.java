package com.scm.contollers;

import com.scm.entities.Video;
import com.scm.repsitories.VideoRepo;
import com.scm.helpers.ResourceNotFoundException;

import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/videos")
@CrossOrigin(origins = "*")
public class VideoController {

    @Autowired
    private VideoRepo videoRepo;

    // DTO for video creation/update requests
    public static class VideoRequest {
        private String title;
        private String description;
        private String hlsPath;

        // Getters and setters
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getHlsPath() { return hlsPath; }
        public void setHlsPath(String hlsPath) { this.hlsPath = hlsPath; }
    }

    // DTO for video responses
    public static class VideoResponse {
        private String id;
        private String title;
        private String description;
        private String uploaderId;
        private String uploadDate;
        private String hlsPath;

        public VideoResponse(Video video) {
            this.id = video.getId();
            this.title = video.getTitle();
            this.description = video.getDescription();
            this.uploaderId = video.getUploaderId();
            this.uploadDate = video.getUploadDate();
            this.hlsPath = video.getHlsPath();
        }

        // Getters
        public String getId() { return id; }
        public String getTitle() { return title; }
        public String getDescription() { return description; }
        public String getUploaderId() { return uploaderId; }
        public String getUploadDate() { return uploadDate; }
        public String getHlsPath() { return hlsPath; }
    }

    // POST /api/videos - Create a new video
    @PostMapping
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<?> createVideo(@Valid @RequestBody VideoRequest request) {
        try {
            // Get current authenticated user
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String uploaderId = authentication.getName(); // Assuming email/username is used as ID

            Video video = new Video();
            video.setTitle(request.getTitle());
            video.setDescription(request.getDescription());
            video.setUploaderId(uploaderId);
            video.setUploadDate(LocalDateTime.now().toString());
            video.setHlsPath(request.getHlsPath());

            Video savedVideo = videoRepo.save(video);
            return ResponseEntity.ok(new VideoResponse(savedVideo));

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to create video: " + e.getMessage()));
        }
    }

    // GET /api/videos - Get all videos
    @GetMapping
    public ResponseEntity<List<VideoResponse>> getAllVideos() {
        try {
            List<Video> videos = videoRepo.findAll();
            List<VideoResponse> response = videos.stream()
                    .map(VideoResponse::new)
                    .toList();
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // GET /api/videos/{id} - Get a specific video
    @GetMapping("/{id}")
    public ResponseEntity<VideoResponse> getVideoById(@PathVariable String id) {
        try {
            Optional<Video> videoOpt = videoRepo.findById(id);
            if (videoOpt.isPresent()) {
                return ResponseEntity.ok(new VideoResponse(videoOpt.get()));
            } else {
                throw new ResourceNotFoundException("Video not found with id: " + id);
            }
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // PUT /api/videos/{id} - Update a video
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<?> updateVideo(@PathVariable String id, @Valid @RequestBody VideoRequest request) {
        try {
            // Get current authenticated user
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String currentUserId = authentication.getName();

            Optional<Video> videoOpt = videoRepo.findById(id);
            if (videoOpt.isEmpty()) {
                throw new ResourceNotFoundException("Video not found with id: " + id);
            }

            Video video = videoOpt.get();

            // Check if the current user is the owner of the video
            if (!video.getUploaderId().equals(currentUserId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "You can only update your own videos"));
            }

            // Update fields
            video.setTitle(request.getTitle());
            video.setDescription(request.getDescription());
            video.setHlsPath(request.getHlsPath());

            Video updatedVideo = videoRepo.save(video);
            return ResponseEntity.ok(new VideoResponse(updatedVideo));

        } catch (ResourceNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to update video: " + e.getMessage()));
        }
    }

    // DELETE /api/videos/{id} - Delete a video
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<?> deleteVideo(@PathVariable String id) {
        try {
            // Get current authenticated user
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String currentUserId = authentication.getName();

            Optional<Video> videoOpt = videoRepo.findById(id);
            if (videoOpt.isEmpty()) {
                throw new ResourceNotFoundException("Video not found with id: " + id);
            }

            Video video = videoOpt.get();

            // Check if the current user is the owner of the video
            if (!video.getUploaderId().equals(currentUserId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "You can only delete your own videos"));
            }

            videoRepo.deleteById(id);
            return ResponseEntity.ok(Map.of("message", "Video deleted successfully"));

        } catch (ResourceNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to delete video: " + e.getMessage()));
        }
    }
}
