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
        String subject = "AFK Live: Video " + status;
        String text = "Hello,\n\nYour video \"" + videoTitle + "\" has been " + status + ".\n\nBest,\nThe AFK Live Team";
        sendEmail(to, subject, text);
    }

    public void sendVerificationEmail(String to, String link) {
        String subject = "Verify your email - AFK Live";
        String text = "Welcome to AFK Live!\n\nPlease click the following link to verify your email address:\n" + link + "\n\nIf you did not sign up, please ignore this email.";
        sendEmail(to, subject, text);
    }

    public void sendPasswordResetEmail(String to, String link) {
        String subject = "Reset your password - AFK Live";
        String text = "Hello,\n\nWe received a request to reset your password. Click the link below to set a new password:\n" + link + "\n\nThis link will expire in 24 hours.";
        sendEmail(to, subject, text);
    }

    private void sendEmail(String to, String subject, String text) {
        if (to == null || to.isEmpty()) {
            log.warn("Skipping email notification: No recipient address.");
            return;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom("noreply@afklive.com");
            message.setTo(to);
            message.setSubject(subject);
            message.setText(text);
            emailSender.send(message);
            log.info("Email sent to {}", to);
        } catch (Exception e) {
            log.warn("Failed to send email to {}: {}", to, e.getMessage());
            // Fallback: Just log it since we might not have valid SMTP config in dev
            log.info("[MOCK EMAIL] To: {}, Subject: {}, Body: {}", to, subject, text);
        }
    }
}
