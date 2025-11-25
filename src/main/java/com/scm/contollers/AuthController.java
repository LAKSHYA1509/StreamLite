package com.scm.contollers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.scm.entities.User;
import com.scm.repsitories.UserRepo;

import jakarta.servlet.http.HttpSession;
import java.util.Map;

@Controller
@RequestMapping("/auth")
public class AuthController {

    @Autowired
    private UserRepo userRepo;

    @PostMapping("/authenticate")
    @ResponseBody
    public ResponseEntity<?> authenticate(@RequestParam("email") String email,
                                         @RequestParam("password") String password,
                                         HttpSession session) {
        try {
            // Find user by email and password (simple authentication for testing)
            User user = userRepo.findByEmailAndPassword(email, password).orElse(null);

            if (user != null) {
                // Store user in session for authentication
                session.setAttribute("user", user);
                session.setAttribute("message", "Login successful!");

                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Login successful",
                    "user", email
                ));
            } else {
                return ResponseEntity.status(401).body(Map.of(
                    "success", false,
                    "message", "Invalid email or password"
                ));
            }

        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "message", "Authentication failed: " + e.getMessage()
            ));
        }
    }
}