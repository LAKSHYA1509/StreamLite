package com.StreamLite.StreamLite.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "Creators")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String roles;

}