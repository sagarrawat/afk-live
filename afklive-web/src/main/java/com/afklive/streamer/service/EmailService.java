package com.afklive.streamer.service;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    @Value("${app.base-url}")
    private String baseUrl;

    private final JavaMailSender emailSender;

    private String createBaseHtml(String title, String bodyContent) {
        return "<html><head><style>@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }</style></head>" +
               "<body style='font-family: Arial, sans-serif; color: #333; line-height: 1.6; background-color: #f9fafb; margin: 0; padding: 20px;'>" +
               "<div style='max-width: 600px; margin: 0 auto; background: #ffffff; padding: 30px; border: 1px solid #eee; border-radius: 12px;'>" +

               // Logo Header
               "<div style='display: flex; align-items: center; gap: 10px; margin-bottom: 30px;'>" +
                   // Icon
                   "<div style='width: 32px; height: 32px; position: relative; border-radius: 50%; border: 3px solid transparent; border-top-color: #667eea; border-right-color: #764ba2; box-sizing: border-box; display: flex; align-items: center; justify-content: center; animation: spin 3s linear infinite;'>" +
                        "<div style='width: 0; height: 0; border-style: solid; border-width: 5px 0 5px 8px; border-color: transparent transparent transparent #764ba2; margin-left: 2px;'></div>" +
                   "</div>" +
                   // Text
                   "<div style='font-size: 20px; font-weight: 800; color: #1a1a2e; letter-spacing: -0.02em; font-family: sans-serif;'>" +
                        "AFK<span style='color: #667eea;'>Live</span>" +
                   "</div>" +
               "</div>" +

               "<h2 style='color: #1a1a2e; margin-top: 0;'>" + title + "</h2>" +
               "<div>" + bodyContent + "</div>" +
               "<div style='margin-top: 30px; font-size: 12px; color: #999; border-top: 1px solid #eee; padding-top: 20px; text-align: center;'>" +
               "&copy; 2026 AFK Live.</div>" +
               "</div></body></html>";
    }

    public void sendUploadNotification(String to, String videoTitle, String status) {
        String content = "<p>Your video <strong>" + videoTitle + "</strong> status has changed to: <strong>" + status + "</strong></p>";
        String html = createBaseHtml("Video Update", content);
        sendEmail(to, "AFK Live: Video " + status, html);
    }

    public void sendVerificationEmail(String to, String link) {
        String content = "<p>Please verify your email address by clicking the button below:</p>" +
                         "<p><a href='" + link + "' style='background: #2c68f6; color: white; padding: 10px 20px; text-decoration: none; border-radius: 5px; display: inline-block;'>Verify Email</a></p>" +
                         "<p>Or copy this link: " + link + "</p>";
        String html = createBaseHtml("Verify your email", content);
        sendEmail(to, "Verify your email - AFK Live", html);
    }

    public void sendPasswordResetEmail(String to, String link) {
        String content = "<p>You requested a password reset. Click below to set a new password:</p>" +
                         "<p><a href='" + link + "' style='background: #2c68f6; color: white; padding: 10px 20px; text-decoration: none; border-radius: 5px; display: inline-block;'>Reset Password</a></p>" +
                         "<p>Or copy this link: " + link + "</p>";
        String html = createBaseHtml("Reset Password", content);
        sendEmail(to, "Reset your password - AFK Live", html);
    }

    public void sendWelcomeEmail(String to) {
        String content = "<p>Welcome to AFK Live! We are excited to help you stream 24/7.</p>" +
                         "<p><a href='" + baseUrl + "'>Go to Studio</a></p>";
        String html = createBaseHtml("Welcome aboard! 🚀", content);
        sendEmail(to, "Welcome to AFK Live! 🚀", html);
    }

    public void sendUpgradeEmail(String to, String planName) {
        String content = "<p>Thank you for upgrading to the <strong>" + planName + "</strong> plan.</p>" +
                         "<p>You now have access to premium features and more storage.</p>";
        String html = createBaseHtml("Upgrade Successful", content);
        sendEmail(to, "You've upgraded to " + planName + "!", html);
    }

    @Async
    public void sendSupportTicket(String userEmail, String category, String message) {
        String subject = "[Support] " + category + " - " + userEmail;
        String content = "<p><strong>User:</strong> " + userEmail + "</p>" +
                         "<p><strong>Category:</strong> " + category + "</p>" +
                         "<hr>" +
                         "<p style='white-space: pre-wrap;'>" + message + "</p>";

        String html = createBaseHtml("New Support Ticket", content);
        sendEmail("support@afklive.in", subject, html);
    }

    private void sendEmail(String to, String subject, String htmlContent) {
        if (to == null || to.isEmpty()) {
            log.warn("Skipping email notification: No recipient address.");
            return;
        }

        try {
            MimeMessage message = emailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom("no-reply@afklive.in");
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
