package com.afklive.streamer.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    @Value("${app.base-url}")
    private String baseUrl;

    @Value("${app.email.support}")
    private String supportEmail;

    @Value("${app.email.no-reply}")
    private String noReplyEmail;

    @Value("${app.zeptomail.url}")
    private String zeptoMailUrl;

    @Value("${app.zeptomail.token}")
    private String zeptoMailToken;

    private final RestTemplate restTemplate;

    // DTOs for ZeptoMail API
    private record EmailAddress(String address, String name) {}

    // Wrapper for recipient to match structure: [{"email_address": {...}}]
    private record ToRecipient(EmailAddress email_address) {}

    private record EmailRequest(
            EmailAddress from,
            List<ToRecipient> to,
            String subject,
            String htmlbody
    ) {}

    private String createBaseHtml(String title, String bodyContent) {
        return "<html><head><meta name='viewport' content='width=device-width, initial-scale=1.0'></head>" +
               "<body style='font-family: Arial, sans-serif; color: #333; line-height: 1.6; background-color: #f9fafb; margin: 0; padding: 0;'>" +
               "<center>" +
               "<table width='100%' border='0' cellpadding='0' cellspacing='0' bgcolor='#f9fafb' style='margin: 0; padding: 20px;'>" +
               "<tr><td align='center'>" +

               // Main Container
               "<table width='600' border='0' cellpadding='0' cellspacing='0' bgcolor='#ffffff' style='max-width: 600px; width: 100%; border: 1px solid #eee; border-radius: 12px; overflow: hidden;'>" +

               // Header with Logo
               "<tr><td style='padding: 30px 30px 20px 30px; text-align: left;'>" +
                   "<a href='" + baseUrl + "' style='text-decoration: none; display: inline-block;'>" +
                       "<span style='font-size: 24px; line-height: 24px;'>🔴</span> " +
                       "<span style='font-size: 24px; font-weight: 800; color: #1a1a2e; font-family: sans-serif; vertical-align: middle;'>AFK<span style='color: #667eea;'>Live</span></span>" +
                   "</a>" +
               "</td></tr>" +

               // Title
               "<tr><td style='padding: 0 30px 10px 30px;'>" +
                   "<h2 style='color: #1a1a2e; margin: 0; font-size: 24px;'>" + title + "</h2>" +
               "</td></tr>" +

               // Body
               "<tr><td style='padding: 0 30px 30px 30px; font-size: 16px; color: #555;'>" +
                   bodyContent +
               "</td></tr>" +

               // Footer
               "<tr><td style='background-color: #f4f5f7; padding: 20px; text-align: center; font-size: 12px; color: #999; border-top: 1px solid #eee;'>" +
                   "&copy; 2026 AFK Live. All rights reserved.<br>" +
                   "<a href='" + baseUrl + "/privacy' style='color: #999; text-decoration: underline;'>Privacy Policy</a> | " +
                   "<a href='" + baseUrl + "/terms' style='color: #999; text-decoration: underline;'>Terms of Service</a>" +
               "</td></tr>" +

               "</table>" + // End Main Container

               "</td></tr></table>" + // End Outer Table
               "</center>" +
               "</body></html>";
    }

    public void sendUploadNotification(String to, String videoTitle, String status) {
        String content = "<p>The status of your video <strong>" + videoTitle + "</strong> has been updated to:</p>" +
                         "<p style='font-size: 18px; font-weight: bold; color: #2c68f6;'>" + status + "</p>" +
                         "<p>You can view your video details in the library.</p>";
        String html = createBaseHtml("Video Status Update", content);
        sendEmail(to, "AFK Live: Video " + status, html);
    }

    public void sendVerificationEmail(String to, String link) {
        String content = "<p>Please verify your email address to get full access to AFK Live features.</p>" +
                         "<p style='margin: 30px 0; text-align: center;'>" +
                         "<a href='" + link + "' style='background: #2c68f6; color: white; padding: 12px 24px; text-decoration: none; border-radius: 6px; display: inline-block; font-weight: bold;'>Verify Email</a>" +
                         "</p>" +
                         "<p style='font-size: 14px; color: #888;'>Or copy this link to your browser:<br><a href='" + link + "' style='color: #2c68f6;'>" + link + "</a></p>";
        String html = createBaseHtml("Verify your email", content);
        sendEmail(to, "Verify your email - AFK Live", html);
    }

    public void sendPasswordResetEmail(String to, String link) {
        String content = "<p>You requested a password reset. Click the button below to set a new password:</p>" +
                         "<p style='margin: 30px 0; text-align: center;'>" +
                         "<a href='" + link + "' style='background: #2c68f6; color: white; padding: 12px 24px; text-decoration: none; border-radius: 6px; display: inline-block; font-weight: bold;'>Reset Password</a>" +
                         "</p>" +
                         "<p style='font-size: 14px; color: #888;'>If you didn't ask to reset your password, you can safely ignore this email.</p>" +
                         "<p style='font-size: 14px; color: #888;'>Or copy this link:<br><a href='" + link + "' style='color: #2c68f6;'>" + link + "</a></p>";
        String html = createBaseHtml("Reset Password", content);
        sendEmail(to, "Reset your password - AFK Live", html);
    }

    public void sendWelcomeEmail(String to) {
        String content = "<p>Welcome to AFK Live! We are excited to have you on board.</p>" +
                         "<p>You are now ready to take your streaming to the next level with our 24/7 cloud streaming platform.</p>" +
                         "<h3 style='color: #333; margin-top: 20px;'>Why streamers love AFK Live:</h3>" +
                         "<ul style='color: #555; padding-left: 20px;'>" +
                             "<li style='margin-bottom: 10px;'><strong>24/7 Streaming:</strong> Keep your channel live around the clock without keeping your computer on.</li>" +
                             "<li style='margin-bottom: 10px;'><strong>Cloud Power:</strong> We handle the heavy lifting so you don't have to worry about hardware limits.</li>" +
                             "<li style='margin-bottom: 10px;'><strong>Easy Setup:</strong> Get your stream up and running in minutes with our intuitive dashboard.</li>" +
                         "</ul>" +
                         "<p style='margin-top: 30px; text-align: center;'>" +
                         "<a href='" + baseUrl + "' style='background: #2c68f6; color: white; padding: 12px 24px; text-decoration: none; border-radius: 6px; display: inline-block; font-weight: bold;'>Go to Studio</a>" +
                         "</p>";
        String html = createBaseHtml("Welcome aboard! 🚀", content);
        sendEmail(to, "Welcome to AFK Live! 🚀", html);
    }

    public void sendUpgradeEmail(String to, String planName) {
        String content = "<p>Congratulations! You have successfully upgraded to the <strong>" + planName + "</strong> plan.</p>" +
                         "<p>You now have access to premium features, increased storage, and priority support.</p>" +
                         "<p>Thank you for supporting AFK Live!</p>" +
                         "<p style='margin-top: 30px; text-align: center;'>" +
                         "<a href='" + baseUrl + "' style='background: #2c68f6; color: white; padding: 12px 24px; text-decoration: none; border-radius: 6px; display: inline-block; font-weight: bold;'>Open Dashboard</a>" +
                         "</p>";
        String html = createBaseHtml("Upgrade Successful", content);
        sendEmail(to, "You've upgraded to " + planName + "!", html);
    }

    @Async
    public void sendSupportTicket(String userEmail, String category, String message) {
        String subject = "[Support] " + category + " - " + userEmail;
        String content = "<p><strong>User:</strong> " + userEmail + "</p>" +
                         "<p><strong>Category:</strong> " + category + "</p>" +
                         "<hr style='border: 0; border-top: 1px solid #eee; margin: 20px 0;'>" +
                         "<p style='white-space: pre-wrap; background: #f9f9f9; padding: 15px; border-radius: 6px; border: 1px solid #eee;'>" + message + "</p>";

        String html = createBaseHtml("New Support Ticket", content);
        sendEmail(supportEmail, subject, html);
    }

    private void sendEmail(String to, String subject, String htmlContent) {
        if (to == null || to.isEmpty()) {
            log.warn("Skipping email notification: No recipient address.");
            return;
        }

        try {
            EmailAddress recipient = new EmailAddress(to, "User");
            ToRecipient toRecipient = new ToRecipient(recipient);

            EmailRequest request = new EmailRequest(
                    new EmailAddress(noReplyEmail, "AFK Live"),
                    Collections.singletonList(toRecipient),
                    subject,
                    htmlContent
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", zeptoMailToken);
            headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

            HttpEntity<EmailRequest> entity = new HttpEntity<>(request, headers);

            restTemplate.postForObject(zeptoMailUrl, entity, String.class);
            log.info("Email sent to {} via ZeptoMail", to);

        } catch (Exception e) {
            log.warn("Failed to send email to {}: {}", to, e.getMessage());
        }
    }
}
