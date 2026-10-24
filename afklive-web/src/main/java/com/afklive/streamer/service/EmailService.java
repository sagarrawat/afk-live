package com.afklive.streamer.service;

import com.afklive.streamer.model.EmailLog;
import com.afklive.streamer.repository.EmailLogRepository;
import com.afklive.streamer.repository.UserRepository;
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
@Slf4j
public class EmailService {

    /** What an email is for; recorded with every send (OPS-1). */
    public enum Kind { VERIFY, RESET, WELCOME, UPGRADE, UPLOAD, DELETION, ALERT, SUPPORT, TEST }

    /** Whether the mail service took an email; {@code error} says why not, in short. */
    public record Result(boolean ok, String error) {
    }

    static final String NOT_CONFIGURED = "Email isn't set up (ZEPTOMAIL_URL / ZEPTOMAIL_TOKEN are empty)";

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
    private final UserRepository users;
    private final EmailLogRepository emailLog;

    @org.springframework.beans.factory.annotation.Autowired
    public EmailService(RestTemplate restTemplate, UserRepository users, EmailLogRepository emailLog) {
        this.restTemplate = restTemplate;
        this.users = users;
        this.emailLog = emailLog;
    }

    /** Without a record of sends (tests of the templates). */
    EmailService(RestTemplate restTemplate) {
        this(restTemplate, null, null);
    }

    /** OPS-1: whether email can be sent at all. When it can't, sign-ups are verified automatically and "Forgot password" says so. */
    public boolean isConfigured() {
        return zeptoMailUrl != null && !zeptoMailUrl.isBlank() && zeptoMailToken != null && !zeptoMailToken.isBlank();
    }

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

    String createBaseHtml(String title, String bodyContent) {
        return "<html><head><meta name='viewport' content='width=device-width, initial-scale=1.0'></head>" +
               "<body style='font-family: Arial, sans-serif; color: #333; line-height: 1.6; background-color: #F4F5FB; margin: 0; padding: 0;'>" +
               "<center>" +
               "<table width='100%' border='0' cellpadding='0' cellspacing='0' bgcolor='#F4F5FB' style='margin: 0; padding: 20px;'>" +
               "<tr><td align='center'>" +

               // Main Container
               "<table width='600' border='0' cellpadding='0' cellspacing='0' bgcolor='#ffffff' style='max-width: 600px; width: 100%; border: 1px solid #DCDFEE; border-radius: 16px; overflow: hidden;'>" +

               // Header: the logo on a night band (docs/brand.md). PNG, as mail clients don't show SVG.
               "<tr><td bgcolor='#10122A' style='background-color: #10122A; padding: 22px 30px;'>" +
                   "<a href='" + baseUrl + "' style='text-decoration: none; display: inline-block;'>" +
                       "<img src='" + baseUrl + "/site/brand/logo-email.png' width='111' height='30' alt='AFK Live' style='display: block; border: 0; color: #EEF0FA; font: 800 20px Arial, sans-serif;'>" +
                   "</a>" +
               "</td></tr>" +

               // Title
               "<tr><td style='padding: 28px 30px 10px 30px;'>" +
                   "<h2 style='color: #10122A; margin: 0; font-size: 24px;'>" + title + "</h2>" +
               "</td></tr>" +

               // Body
               "<tr><td style='padding: 0 30px 30px 30px; font-size: 16px; color: #555;'>" +
                   bodyContent +
               "</td></tr>" +

               // Footer
               "<tr><td style='background-color: #f4f5f7; padding: 20px; text-align: center; font-size: 12px; color: #999; border-top: 1px solid #eee;'>" +
                   "&copy; AFK Live. Stay live while you're away.<br>" +
                   "<a href='" + baseUrl + "/privacy' style='color: #999; text-decoration: underline;'>Privacy Policy</a> | " +
                   "<a href='" + baseUrl + "/terms' style='color: #999; text-decoration: underline;'>Terms of Service</a> | " +
                   // RE-7: the public status page.
                   "<a href='" + baseUrl + "/status' style='color: #999; text-decoration: underline;'>Service status</a>" +
               "</td></tr>" +

               "</table>" + // End Main Container

               "</td></tr></table>" + // End Outer Table
               "</center>" +
               "</body></html>";
    }

    public void sendUploadNotification(String to, String videoTitle, String status) {
        String content = "<p>The status of your video <strong>" + videoTitle + "</strong> has been updated to:</p>" +
                         "<p style='font-size: 18px; font-weight: bold; color: #21245C;'>" + status + "</p>" +
                         "<p>You can view your video details in the library.</p>";
        String html = createBaseHtml("Video Status Update", content);
        sendEmail(Kind.UPLOAD, to, "AFK Live: Video " + status, html);
    }

    public void sendVerificationEmail(String to, String link) {
        String content = "<p>Please verify your email address to get full access to AFK Live features.</p>" +
                         "<p style='margin: 30px 0; text-align: center;'>" +
                         "<a href='" + link + "' style='background: #21245C; color: #ffffff; padding: 12px 26px; text-decoration: none; border-radius: 999px; display: inline-block; font-weight: bold;'>Verify Email</a>" +
                         "</p>" +
                         "<p style='font-size: 14px; color: #888;'>Or copy this link to your browser:<br><a href='" + link + "' style='color: #21245C;'>" + link + "</a></p>";
        String html = createBaseHtml("Verify your email", content);
        sendEmail(Kind.VERIFY, to, "Verify your email - AFK Live", html);
    }

    public void sendPasswordResetEmail(String to, String link) {
        String content = "<p>You requested a password reset. Click the button below to set a new password:</p>" +
                         "<p style='margin: 30px 0; text-align: center;'>" +
                         "<a href='" + link + "' style='background: #21245C; color: #ffffff; padding: 12px 26px; text-decoration: none; border-radius: 999px; display: inline-block; font-weight: bold;'>Reset Password</a>" +
                         "</p>" +
                         "<p style='font-size: 14px; color: #888;'>If you didn't ask to reset your password, you can safely ignore this email.</p>" +
                         "<p style='font-size: 14px; color: #888;'>Or copy this link:<br><a href='" + link + "' style='color: #21245C;'>" + link + "</a></p>";
        String html = createBaseHtml("Reset Password", content);
        sendEmail(Kind.RESET, to, "Reset your password - AFK Live", html);
    }

    /** ON-7: the account will be deleted on {@code when}; signing in offers to keep it until then. */
    public void sendDeletionScheduledEmail(String to, String when) {
        String content = "<p>You asked us to delete your AFK Live account. Your streams are stopped and the account is closed.</p>"
                + "<p>We'll delete your streams, videos and settings on <strong>" + escape(when) + "</strong>.</p>"
                + "<p>Changed your mind? Sign in before then and choose <strong>Keep my account</strong>.</p>"
                + "<p style='margin-top: 30px; text-align: center;'><a href='" + escape(baseUrl + "/login") + "' style='background: #21245C; "
                + "color: #ffffff; padding: 12px 26px; text-decoration: none; border-radius: 999px; display: inline-block; font-weight: bold;'>Sign in</a></p>"
                + "<p style='font-size:13px;color:#999'>Didn't ask for this? Sign in now to keep your account, then change your password.</p>";
        sendEmail(Kind.DELETION, to, "Your AFK Live account will be deleted on " + when, createBaseHtml("Your account is scheduled for deletion", content));
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
                         "<a href='" + baseUrl + "' style='background: #21245C; color: #ffffff; padding: 12px 26px; text-decoration: none; border-radius: 999px; display: inline-block; font-weight: bold;'>Go to Studio</a>" +
                         "</p>";
        String html = createBaseHtml("Welcome aboard! 🚀", content);
        sendEmail(Kind.WELCOME, to, "Welcome to AFK Live! 🚀", html);
    }

    public void sendUpgradeEmail(String to, String planName) {
        String content = "<p>Congratulations! You have successfully upgraded to the <strong>" + planName + "</strong> plan.</p>" +
                         "<p>You now have access to premium features, increased storage, and priority support.</p>" +
                         "<p>Thank you for supporting AFK Live!</p>" +
                         "<p style='margin-top: 30px; text-align: center;'>" +
                         "<a href='" + baseUrl + "' style='background: #21245C; color: #ffffff; padding: 12px 26px; text-decoration: none; border-radius: 999px; display: inline-block; font-weight: bold;'>Open Dashboard</a>" +
                         "</p>";
        String html = createBaseHtml("Upgrade Successful", content);
        sendEmail(Kind.UPGRADE, to, "You've upgraded to " + planName + "!", html);
    }

    @Async
    public void sendSupportTicket(String userEmail, String category, String message) {
        String subject = "[Support] " + category + " - " + userEmail;
        String content = "<p><strong>User:</strong> " + userEmail + "</p>" +
                         "<p><strong>Category:</strong> " + category + "</p>" +
                         "<hr style='border: 0; border-top: 1px solid #eee; margin: 20px 0;'>" +
                         "<p style='white-space: pre-wrap; background: #f9f9f9; padding: 15px; border-radius: 6px; border: 1px solid #eee;'>" + message + "</p>";

        String html = createBaseHtml("New Support Ticket", content);
        sendEmail(Kind.SUPPORT, supportEmail, subject, html);
    }

    /**
     * A stream or account alert. Every text value is escaped (stream names come from users).
     * Returns whether the email was handed to the mail service.
     */
    public boolean sendAlert(String to, String subject, String title, String body, String link) {
        String content = "<p>" + escape(body) + "</p>"
                + (link == null ? "" : "<p><a href='" + escape(baseUrl + link) + "' style='display:inline-block;background:#21245C;"
                + "color:#fff;padding:10px 22px;border-radius:999px;text-decoration:none;font-weight:600'>Open AFK Live</a></p>")
                + "<p style='font-size:13px;color:#999'>Is it just you? <a href='" + escape(baseUrl + "/status")
                + "' style='color:#999'>Check AFK Live's status</a>. You can turn alert emails off in Settings.</p>";
        return sendEmail(Kind.ALERT, to, subject, createBaseHtml(escape(title), content)).ok();
    }

    static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** OPS-1: "Send a test email" in the admin console, to the admin's own address. */
    public Result sendTestEmail(String to) {
        String content = "<p>This is a test from the AFK Live admin console. If you can read it, email works.</p>";
        return sendEmail(Kind.TEST, to, "AFK Live test email", createBaseHtml("Email works", content));
    }

    private Result sendEmail(Kind kind, String to, String subject, String htmlContent) {
        Result result = deliver(to, subject, htmlContent);
        record(kind, to, result);
        return result;
    }

    private Result deliver(String to, String subject, String htmlContent) {
        if (to == null || to.isEmpty()) {
            log.warn("Skipping email notification: No recipient address.");
            return new Result(false, "No recipient address");
        }
        if (!isConfigured()) {
            log.debug("Email is not configured; not sending \"{}\"", subject);
            return new Result(false, NOT_CONFIGURED);
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
            return new Result(true, null);
        } catch (Exception e) {
            log.warn("Failed to send email to {}: {}", to, e.getMessage());
            return new Result(false, shortError(e.getMessage(), to));
        }
    }

    /** The mail service's error, without the recipient's address, short enough for the log table. */
    static String shortError(String message, String to) {
        String m = message == null || message.isBlank() ? "The mail service refused it" : message.replaceAll("\\s+", " ").trim();
        if (to != null && !to.isBlank()) {
            m = m.replace(to, "[recipient]");
        }
        return m.length() > 300 ? m.substring(0, 297) + "..." : m;
    }

    /** Records the attempt; never fails the caller. */
    private void record(Kind kind, String to, Result result) {
        if (emailLog == null) {
            return;
        }
        try {
            EmailLog row = new EmailLog();
            row.setCreatedAt(java.time.ZonedDateTime.now(java.time.ZoneId.of("UTC")));
            row.setKind(kind.name());
            row.setUserId(to == null || users == null ? null : users.findByEmail(to).map(u -> u.getId()).orElse(null));
            row.setOk(result.ok());
            row.setError(result.ok() ? null : result.error());
            emailLog.save(row);
        } catch (Exception e) {
            log.debug("Could not record an email send: {}", e.getMessage());
        }
    }
}
