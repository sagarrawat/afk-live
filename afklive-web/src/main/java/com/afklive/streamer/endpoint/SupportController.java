package com.afklive.streamer.endpoint;

import com.afklive.streamer.service.EmailService;
import com.afklive.streamer.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/support")
@RequiredArgsConstructor
public class SupportController {

    private final EmailService emailService;

    @PostMapping("/ticket")
    public ResponseEntity<?> submitTicket(
            @RequestParam("category") String category,
            @RequestParam("message") String message,
            @RequestParam(value = "file", required = false) MultipartFile file,
            Principal principal) {

        if (principal == null) return ResponseEntity.status(401).build();

        String email = SecurityUtils.getEmail(principal);

        if (category == null || message == null || message.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Category and message are required"));
        }

        emailService.sendSupportTicket(email, category, message, file);

        return ResponseEntity.ok(Map.of("success", true, "message", "Ticket submitted"));
    }
}
