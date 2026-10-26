package com.afklive.streamer.endpoint;

import com.afklive.streamer.model.SupportTicket;
import com.afklive.streamer.repository.SupportTicketRepository;
import com.afklive.streamer.service.EmailService;
import com.afklive.streamer.service.FileStorageService;
import com.afklive.streamer.service.UserService;
import com.afklive.streamer.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.Principal;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * "Contact support" in the app (Help page and account menu): a ticket that admins see under Support,
 * with an optional screenshot, recording or PDF.
 */
@RestController
@RequestMapping("/api/support")
@RequiredArgsConstructor
public class SupportController {

    /** What the app's form offers; anything else is filed as "Other". */
    static final Set<String> CATEGORIES = Set.of("Streaming", "Destinations", "Uploads", "Billing", "Account", "Other");
    static final int MAX_MESSAGE = 5000;
    static final long MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024;
    static final Set<String> ATTACHMENT_TYPES = Set.of("png", "jpg", "jpeg", "pdf", "mp4");

    private final EmailService emailService;
    private final SupportTicketRepository supportTicketRepository;
    private final FileStorageService storageService;
    private final UserService userService;

    @PostMapping("/ticket")
    public ResponseEntity<?> submitTicket(
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "message", required = false) String message,
            @RequestParam(value = "file", required = false) MultipartFile file,
            Principal principal) {

        if (principal == null) return ResponseEntity.status(401).build();

        String email = SecurityUtils.getEmail(principal);

        if (message == null || message.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Tell us what happened."));
        }
        if (message.length() > MAX_MESSAGE) {
            return ResponseEntity.badRequest().body(Map.of("message", "Keep the message under " + MAX_MESSAGE + " characters."));
        }
        String filedAs = category != null && CATEGORIES.contains(category.trim()) ? category.trim() : "Other";

        String attachmentKey = null;
        String attachmentName = null;

        if (file != null && !file.isEmpty()) {
            attachmentName = file.getOriginalFilename();
            // Sanitize filename to prevent directory traversal or weird characters
            String safeName = "file";
            if (attachmentName != null) {
                safeName = new java.io.File(attachmentName).getName().replaceAll("[^a-zA-Z0-9._-]", "_");
            }
            int dot = safeName.lastIndexOf('.');
            String ext = dot < 0 ? "" : safeName.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (!ATTACHMENT_TYPES.contains(ext)) {
                return ResponseEntity.badRequest().body(Map.of("message", "Attach a screenshot (PNG or JPG), a PDF or an MP4 recording."));
            }
            if (file.getSize() > MAX_ATTACHMENT_BYTES) {
                return ResponseEntity.badRequest().body(Map.of("message", "The attachment is too big. Keep it under 25 MB."));
            }
            try {
                // Prefix helps identify support files in storage
                attachmentKey = storageService.uploadFile(file.getInputStream(), "support_" + safeName, file.getSize());
            } catch (IOException e) {
                return ResponseEntity.status(500).body(Map.of("message", "The attachment didn't upload. Try again, or send without it."));
            }
        }

        SupportTicket ticket = SupportTicket.builder()
                .user(userService.getOrCreateUser(email))
                .category(filedAs)
                .message(message.trim())
                .attachmentKey(attachmentKey)
                .attachmentName(attachmentName)
                .status("OPEN")
                .build();

        SupportTicket saved = supportTicketRepository.save(ticket);

        emailService.sendSupportTicket(email, filedAs, message.trim());

        return ResponseEntity.ok(Map.of("success", true, "id", saved.getId() == null ? 0 : saved.getId(), "message", "Ticket submitted"));
    }
}
