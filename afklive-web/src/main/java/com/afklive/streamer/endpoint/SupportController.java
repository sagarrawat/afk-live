package com.afklive.streamer.endpoint;

import com.afklive.streamer.model.SupportTicket;
import com.afklive.streamer.repository.SupportTicketRepository;
import com.afklive.streamer.service.EmailService;
import com.afklive.streamer.service.FileStorageService;
import com.afklive.streamer.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/support")
@RequiredArgsConstructor
public class SupportController {

    private final EmailService emailService;
    private final SupportTicketRepository supportTicketRepository;
    private final FileStorageService storageService;

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

        String attachmentKey = null;
        String attachmentName = null;

        if (file != null && !file.isEmpty()) {
            try {
                attachmentName = file.getOriginalFilename();
                // Sanitize filename to prevent directory traversal or weird characters
                String safeName = "file";
                if (attachmentName != null) {
                    safeName = new java.io.File(attachmentName).getName().replaceAll("[^a-zA-Z0-9._-]", "_");
                }

                // Prefix helps identify support files in storage
                attachmentKey = storageService.uploadFile(file.getInputStream(), "support_" + safeName, file.getSize());
            } catch (IOException e) {
                return ResponseEntity.status(500).body(Map.of("message", "Failed to upload attachment"));
            }
        }

        SupportTicket ticket = SupportTicket.builder()
                .userEmail(email)
                .category(category)
                .message(message)
                .attachmentKey(attachmentKey)
                .attachmentName(attachmentName)
                .status("OPEN")
                .build();

        supportTicketRepository.save(ticket);

        emailService.sendSupportTicket(email, category, message);

        return ResponseEntity.ok(Map.of("success", true, "message", "Ticket submitted"));
    }
}
