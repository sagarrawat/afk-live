package com.afklive.streamer.service;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    @Value("${app.base-url}")
    private String baseUrl;

    private final JavaMailSender emailSender;
    private final TemplateEngine templateEngine;

    public void sendUploadNotification(String to, String videoTitle, String status) {
        Context context = new Context();
        context.setVariable("videoTitle", videoTitle);
        context.setVariable("status", status);
        String html = templateEngine.process("email/notification", context);
        sendEmail(to, "AFK Live: Video " + status, html);
    }

    public void sendVerificationEmail(String to, String link) {
        Context context = new Context();
        context.setVariable("link", link);
        String html = templateEngine.process("email/verification", context);
        sendEmail(to, "Verify your email - AFK Live", html);
    }

    public void sendPasswordResetEmail(String to, String link) {
        Context context = new Context();
        context.setVariable("link", link);
        String html = templateEngine.process("email/reset-password", context);
        sendEmail(to, "Reset your password - AFK Live", html);
    }

    public void sendWelcomeEmail(String to) {
        Context context = new Context();
        context.setVariable("baseUrl", baseUrl);
        String html = templateEngine.process("email/welcome", context);
        sendEmail(to, "Welcome to AFK Live! 🚀", html);
    }

    public void sendUpgradeEmail(String to, String planName) {
        Context context = new Context();
        context.setVariable("planName", planName);
        context.setVariable("baseUrl", baseUrl);
        String html = templateEngine.process("email/upgrade", context);
        sendEmail(to, "You've upgraded to " + planName + "!", html);
    }

    private void sendEmail(String to, String subject, String htmlContent) {
        if (to == null || to.isEmpty()) {
            log.warn("Skipping email notification: No recipient address.");
            return;
        }

        try {
            MimeMessage message = emailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom("noreply@afklive.com");
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlContent, true); // true = html

            emailSender.send(message);
            log.info("Email sent to {}", to);
        } catch (Exception e) {
            log.warn("Failed to send email to {}: {}", to, e.getMessage());
            // Fallback: Just log it since we might not have valid SMTP config in dev
            log.info("[MOCK EMAIL] To: {}, Subject: {}, Body: {}", to, subject, htmlContent);
        }
    }
}
