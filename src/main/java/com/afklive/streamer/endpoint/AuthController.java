package com.afklive.streamer.endpoint;

import com.afklive.streamer.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/register")
    public String register() {
        return "register";
    }

    @PostMapping("/register")
    public String registerUser(@RequestParam String email, @RequestParam String password, @RequestParam String name, Model model) {
        try {
            userService.registerUser(email, password, name);
            model.addAttribute("success", "Registration successful. Please check your email to verify your account.");
            return "login";
        } catch (Exception e) {
            model.addAttribute("error", e.getMessage());
            return "register";
        }
    }

    @GetMapping("/verify-email")
    public String verifyEmail(@RequestParam String token, Model model) {
        if (userService.verifyEmail(token)) {
            model.addAttribute("success", "Email verified. You can now login.");
        } else {
            model.addAttribute("error", "Invalid or expired verification link.");
        }
        return "login";
    }

    @GetMapping("/forgot-password")
    public String forgotPassword() {
        return "forgot-password";
    }

    @PostMapping("/forgot-password")
    public String processForgotPassword(@RequestParam String email, Model model) {
        userService.requestPasswordReset(email);
        model.addAttribute("success", "If an account exists, a password reset link has been sent.");
        return "login";
    }

    @GetMapping("/reset-password")
    public String resetPassword(@RequestParam String token, Model model) {
        model.addAttribute("token", token);
        return "reset-password";
    }

    @PostMapping("/reset-password")
    public String processResetPassword(@RequestParam String token, @RequestParam String password, Model model) {
        if (userService.resetPassword(token, password)) {
            model.addAttribute("success", "Password reset successfully. Please login.");
            return "login";
        } else {
            model.addAttribute("error", "Invalid or expired token.");
            return "reset-password";
        }
    }
}
