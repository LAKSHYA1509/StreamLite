package com.scm.contollers;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.ui.Model;

@Controller
public class PlayerController {

    @GetMapping("/player/{videoId}")
    public String showPlayer(@PathVariable String videoId, Model model) {
        model.addAttribute("videoId", videoId);
        return "player";
    }
}
