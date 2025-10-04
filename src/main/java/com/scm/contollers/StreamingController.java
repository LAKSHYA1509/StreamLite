package com.scm.contollers;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.scm.entities.Video;
import com.scm.repsitories.VideoRepo;

import java.time.LocalDate;

@RestController
public class StreamingController {
    
    @Autowired
    private VideoRepo videoRepo;

    @GetMapping("/stream/{id}/{fileName}")
    public ResponseEntity<byte[]> streamVideoSegment(@PathVariable String id, @PathVariable String fileName) {
        Optional<Video> videoOpt = videoRepo.findById(id);
        if (videoOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Video video = videoOpt.get();
        Path hlsFilePath = Paths.get(video.getHlsPath());
        Path hlsDir = hlsFilePath.getParent();
        Path filePath = hlsDir.resolve(fileName);
        try {
            byte[] videoBytes = Files.readAllBytes(filePath);
            return ResponseEntity.ok().header("Content-Type", "video/mp2t").body(videoBytes);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/upload")
    public ResponseEntity<String> uploadSpecificVideo(@RequestParam("file") MultipartFile file) {
        try {
            // Ensure the directory exists
            File inputDir = new File("videos/input/");
            if (!inputDir.exists()) {
                inputDir.mkdirs(); // Create the directory if it doesn't exist
            }

            // Check if the uploaded file is "videoplayback.mp4"
            if (!"videoplayback.mp4".equals(file.getOriginalFilename())) {
                return ResponseEntity.badRequest().body("Only 'videoplayback.mp4' is allowed.");
            }

            // Save the file locally and segment it
            String filePath = "videos/input/" + file.getOriginalFilename();
            file.transferTo(new File(filePath));
            
            // Call the segmentation script
            ProcessBuilder processBuilder = new ProcessBuilder("segment_video.bat", filePath);
            processBuilder.directory(new File(""));
            Process process = processBuilder.start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Segmentation script failed.");
            }

            Video video = new Video();
            video.setTitle(file.getOriginalFilename());
            video.setUploaderId("uploader123");
            video.setUploadDate(LocalDate.now().toString());
            video.setHlsPath("videos/output/playlist.m3u8");
            videoRepo.save(video);

            return ResponseEntity.ok("Video uploaded and segmented successfully!");
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to upload video.");
        }
    }
}
