package com.afklive.streamer.endpoint;

import com.afklive.streamer.service.EmailService;
import com.afklive.streamer.service.UserService;
import com.afklive.streamer.util.SecurityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.security.Principal;
import java.util.Map;

@Controller
public class AuthController {

    /** OPS-1: what "Forgot password" says when no email can be sent. */
    static final String EMAIL_UNAVAILABLE = "We can't send email right now, so we can't send a reset link. "
            + "Write to support@afklive.in from the address you signed up with and we'll help you in. "
            + "If you signed up with Google, use \"Continue with Google\" on the sign-in page.";

    private final UserService userService;
    private final EmailService emailService;
    /**
     * Links in emails use the configured public address (APP_BASE_URL), never the request's Host: behind
     * the proxy that can be an internal address, and a forged Host header must not end up in a reset link.
     */
    private final String baseUrl;

    public AuthController(UserService userService, EmailService emailService,
                          @Value("${app.base-url:http://localhost:8080}") String baseUrl) {
        this.userService = userService;
        this.emailService = emailService;
        this.baseUrl = publicBaseUrl(baseUrl);
    }

    static String publicBaseUrl(String configured) {
        String url = configured == null ? "" : configured.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    // GET /login is in SignInStepController (it also offers to keep an account scheduled for deletion).

    @GetMapping("/register")
    public String register() {
        return "register";
    }

    @PostMapping("/register")
    public String registerUser(@RequestParam String email, @RequestParam String password, @RequestParam String name, Model model) {
        String passwordRegex = "^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=.*[@#$%^&+=!_\\-]).{8,}$";
        if (!password.matches(passwordRegex)) {
            model.addAttribute("error", "Password must be at least 8 characters long, contain at least one digit, one uppercase letter, one lowercase letter, and one special character.");
            return "register";
        }

        try {
            UserService.Registration r = userService.registerUser(email, password, name, baseUrl);
            model.addAttribute("success", r == UserService.Registration.READY
                    ? "Your account is ready. Sign in to start."
                    : "Registration successful. Please check your email to verify your account.");
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
    public String forgotPassword(Model model) {
        if (!emailService.isConfigured()) {
            model.addAttribute("emailUnavailable", EMAIL_UNAVAILABLE);
        }
        return "forgot-password";
    }

    @PostMapping("/forgot-password")
    public String processForgotPassword(@RequestParam String email, Model model) {
        if (!userService.requestPasswordReset(email, baseUrl)) {
            model.addAttribute("emailUnavailable", EMAIL_UNAVAILABLE);
            return "forgot-password";
        }
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
            model.addAttribute("token", token);
            return "reset-password";
        }
    }

    @ResponseBody
    @PostMapping("/api/auth/resend-verification")
    public ResponseEntity<?> resendVerification(Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        try {
            userService.resendVerification(SecurityUtils.getEmail(principal), baseUrl);
            return ResponseEntity.ok(Map.of("success", true, "message", "Verification email sent."));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * OPS-2: makes sure the browser holds a CSRF token (the cookie is set on every response). The app
     * calls this after a "page expired" 403 and then retries once.
     */
    @ResponseBody
    @GetMapping("/api/auth/csrf")
    public ResponseEntity<Void> csrf() {
        return ResponseEntity.noContent().build();
    }
}
