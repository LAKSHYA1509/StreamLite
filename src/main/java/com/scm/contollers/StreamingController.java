package com.scm.contollers;

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
import org.springframework.web.bind.annotation.RestController;

import com.scm.entities.Video;
import com.scm.repsitories.VideoRepo;

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

}
