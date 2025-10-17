package com.scm.contollers;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class ErrorController implements org.springframework.boot.web.servlet.error.ErrorController {

    @RequestMapping("/error")
    public String handleError(Model model) {
        // You can add error details to the model if needed
        model.addAttribute("error", "An unexpected error occurred");
        model.addAttribute("message", "We're sorry, but something went wrong. Please try again later.");
        return "error";
    }
}