package com.scm.repsitories;

import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.JpaRepository;
import com.scm.entities.Video;

@Repository
public interface VideoRepo extends JpaRepository<Video, String> {
    
}
