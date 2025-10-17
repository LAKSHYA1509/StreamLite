package com.scm.entities;

import jakarta.persistence.Entity;
import jakarta.persistence.*;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.util.UUID;

@Entity
@Data
@Table(name = "videos")
public class Video {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @NotBlank(message = "Title is required")
    @Size(max = 255, message = "Title must not exceed 255 characters")
    private String title;

    @Size(max = 1000, message = "Description must not exceed 1000 characters")
    private String description;

    @NotBlank(message = "Uploader ID is required")
    private String uploaderId;

    private String uploadDate;

    @Size(max = 500, message = "HLS path must not exceed 500 characters")
    private String hlsPath;

    public String getHlsPath() {
        return hlsPath;
    }

    public void setHlsPath(String hlsPath) {
        this.hlsPath = hlsPath;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public void setUploaderId(String uploaderId) {
        this.uploaderId = uploaderId;
    }

    public void setUploadDate(String uploadDate) {
        this.uploadDate = uploadDate;
    }

    public String getId() {
        return id;
    }

}
