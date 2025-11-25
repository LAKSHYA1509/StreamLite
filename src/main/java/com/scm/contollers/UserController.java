package com.scm.contollers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.UUID;
import com.scm.entities.User;
import com.scm.services.UserService;

import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;    
@Controller
@RequestMapping("/user")
public class UserController {

    private Logger logger = LoggerFactory.getLogger(UserController.class);

    @Autowired
    private UserService userService;

    // This part is for your regular dashboard page, it stays the same
    @GetMapping("/dashboard")
    public String userDashboard() {
        return "user/dashboard";
    }

    @RequestMapping("/profile")
    public String userProfile(Model model, Authentication authentication) {
        return "user/profile";
    }
    
    // ... your profile page method ...

    // --- THIS IS THE CORRECTED API ENDPOINT ---
    @PostMapping("/generate-stream-key") // 1. Simplified the path. The full path is now /user/generate-stream-key
    @ResponseBody // 2. Tells Spring to return the raw String as data, not a view name.
    public ResponseEntity<String> generateStreamKey(Authentication authentication) {
        try {
            if (authentication == null) {
                logger.error("Authentication is null");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Authentication required");
            }
            String email = null;
            Object principal = authentication.getPrincipal();
            if (principal instanceof OidcUser) {
            OidcUser oidcUser = (OidcUser) principal;
            email = oidcUser.getEmail();
        } else if (principal instanceof OAuth2User) {
            OAuth2User oauth2User = (OAuth2User) principal;
            email = oauth2User.getAttribute("email");
        }
        // 3. If not OAuth2, it's a regular form login, so getName() is correct
        else {
            email = authentication.getName();
        }

        if (email == null) {
            logger.error("Could not determine user email from principal");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Could not determine user email");
        }
        logger.info("Generating stream key for user: " + email);
        User user = userService.getUserByEmail(email);

        if (user != null) {
            if (user.getStreamKey() == null || user.getStreamKey().isEmpty()) {
                String streamKey = UUID.randomUUID().toString();
                user.setStreamKey(streamKey);
                userService.updateUser(user);
                logger.info("Generated new stream key for user: " + email);
            } else {
                logger.info("Using existing stream key for user: " + email);
            }
            return ResponseEntity.ok(user.getStreamKey());
        }

        logger.error("User not found with email: " + email);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body("User not found");
    } catch (Exception e) {
        logger.error("Error generating stream key: ", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error generating key: " + e.getMessage());
    }
}
}