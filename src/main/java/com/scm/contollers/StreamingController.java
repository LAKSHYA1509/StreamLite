package com.scm.contollers;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.scm.entities.Video;
import com.scm.repsitories.VideoRepo;

@RestController
public class StreamingController {

    @Autowired
    private VideoRepo videoRepo;

    // ─────────────────────────────────────────────────────────────
    // STREAM HLS SEGMENTS
    // ─────────────────────────────────────────────────────────────
    @GetMapping("/stream/{id}/{fileName}")
    public ResponseEntity<byte[]> streamVideoSegment(@PathVariable String id, @PathVariable String fileName) {
        try {
            Optional<Video> videoOpt = videoRepo.findById(id);
            if (videoOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }

            Video video = videoOpt.get();
            Path hlsFilePath = Paths.get(video.getHlsPath());
            Path hlsDir = hlsFilePath.getParent();
            Path filePath = hlsDir.resolve(fileName);

            if (!Files.exists(filePath)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(("Segment not found: " + fileName).getBytes());
            }

            byte[] videoBytes = Files.readAllBytes(filePath);
            return ResponseEntity.ok()
                    .header("Content-Type", "video/mp2t")
                    .body(videoBytes);

        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(("Error reading segment: " + e.getMessage()).getBytes());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // UPLOAD AND SEGMENT VIDEO
    // ─────────────────────────────────────────────────────────────
    @PostMapping("/upload")
    public ResponseEntity<String> uploadSpecificVideo(@RequestParam("file") MultipartFile file) {
        String baseDir = System.getProperty("user.dir") + File.separator + "videos" + File.separator + "input";

        try {
            // Ensure input directory exists
            File inputDir = new File(baseDir);
            if (!inputDir.exists()) {
                inputDir.mkdirs();
            }

            // Validate file type
            if (!file.getOriginalFilename().endsWith(".mp4")) {
                return ResponseEntity.badRequest().body("Only MP4 files are supported.");
            }

            File dest = new File(inputDir, file.getOriginalFilename());

            // Safely copy the uploaded file
            try (var inputStream = file.getInputStream()) {
                Files.copy(inputStream, dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            // Delay for Windows file handle release
            Thread.sleep(300);

            // Run segmentation batch script (FFmpeg)
            ProcessBuilder processBuilder = new ProcessBuilder("segment_video.bat", dest.getAbsolutePath());
            processBuilder.directory(new File(System.getProperty("user.dir"))); // run in project root
            processBuilder.inheritIO(); // show FFmpeg output in console
            Process process = processBuilder.start();

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("Segmentation script failed (exit code " + exitCode + ").");
            }

            // Save video metadata in DB
            Video video = new Video();
            video.setTitle(file.getOriginalFilename());
            video.setUploaderId("uploader123");
            video.setUploadDate(LocalDateTime.now().toString());
            video.setHlsPath("videos/output/playlist.m3u8");
            videoRepo.save(video);

            return ResponseEntity.ok("Video uploaded and segmented successfully at: " + dest.getAbsolutePath());

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to upload video: " + e.getMessage());
        }
    }
}
