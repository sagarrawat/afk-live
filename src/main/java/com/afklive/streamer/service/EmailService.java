package com.afklive.streamer.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender emailSender;

    public void sendUploadNotification(String to, String videoTitle, String status) {
        if (to == null || to.isEmpty()) {
            log.warn("Skipping email notification: No recipient address.");
            return;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom("noreply@afklive.com");
            message.setTo(to);
            message.setSubject("AFK Live: Video " + status);
            message.setText("Hello,\n\nYour video \"" + videoTitle + "\" has been " + status + ".\n\nBest,\nThe AFK Live Team");
            emailSender.send(message);
            log.info("Email sent to {}", to);
        } catch (Exception e) {
            log.warn("Failed to send email to {}: {}", to, e.getMessage());
            // Fallback: Just log it since we might not have valid SMTP config in dev
            log.info("[MOCK EMAIL] To: {}, Subject: Video {}, Body: Your video '{}' is {}", to, status, videoTitle, status);
        }
    }
}
