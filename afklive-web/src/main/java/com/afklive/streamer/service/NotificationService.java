package com.afklive.streamer.service;

import com.afklive.streamer.model.Notification;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.NotificationRepository;
import com.afklive.streamer.repository.UserRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Alerts for creators (NT-1, NT-2, SC-5): stored for the in-app list and emailed when the creator
 * allows it. Repeats are suppressed with a dedupe key and a window ("not repeated more than daily").
 * Emails go out on a background thread so detection never waits on the mail service.
 */
@Service
@Slf4j
public class NotificationService {

    /** What an alert is about; the frontend uses it for icons and grouping. */
    public static final class Types {
        public static final String STREAM_DOWN = "STREAM_DOWN";
        public static final String STREAM_RECOVERED = "STREAM_RECOVERED";
        public static final String DESTINATION_DOWN = "DESTINATION_DOWN";
        public static final String DESTINATION_RECOVERED = "DESTINATION_RECOVERED";
        public static final String PHONE_LOST = "PHONE_LOST";
        public static final String PHONE_BACK = "PHONE_BACK";
        public static final String STREAM_FAILED = "STREAM_FAILED";
        public static final String START_STUCK = "START_STUCK";
        public static final String SCHEDULE_FAILED = "SCHEDULE_FAILED";
        public static final String STORAGE_ALMOST_FULL = "STORAGE_ALMOST_FULL";
        public static final String PAYMENT_FAILED = "PAYMENT_FAILED";
        public static final String PLAN_LIMIT = "PLAN_LIMIT";
        public static final String CREDIT_LIMIT = "CREDIT_LIMIT";
        public static final String ITEM_FAILED = "ITEM_FAILED";

        private Types() {
        }
    }

    private final NotificationRepository notifications;
    private final UserRepository users;
    private final EmailService email;
    private final ExecutorService mailer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "alert-mailer");
        t.setDaemon(true);
        return t;
    });

    public NotificationService(NotificationRepository notifications, UserRepository users, EmailService email) {
        this.notifications = notifications;
        this.users = users;
        this.email = email;
    }

    /** One alert to send. {@code dedupeKey} + {@code dedupeWindow}: skip if the same key was sent within the window. */
    public record Alert(String type, Notification.Level level, Long streamId, String title, String body, String link,
                        String dedupeKey, Duration dedupeWindow, boolean emailIt) {
    }

    /** Stores the alert and emails it (if allowed). Returns the stored notification, or empty if it was a repeat. */
    public Optional<Notification> notify(String userEmail, Alert a) {
        Optional<User> user = users.findByEmail(userEmail);
        if (user.isEmpty()) {
            return Optional.empty();
        }
        return notify(user.get(), a);
    }

    public Optional<Notification> notify(User user, Alert a) {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));
        if (a.dedupeKey() != null && a.dedupeWindow() != null
                && notifications.existsByUserIdAndDedupeKeyAndCreatedAtAfter(user.getId(), a.dedupeKey(), now.minus(a.dedupeWindow()))) {
            return Optional.empty();
        }
        Notification n = new Notification();
        n.setUserId(user.getId());
        n.setType(a.type());
        n.setLevel(a.level());
        n.setStreamId(a.streamId());
        n.setTitle(trim(a.title(), 200));
        n.setBody(a.body());
        n.setLink(a.link());
        n.setDedupeKey(a.dedupeKey() == null ? null : trim(a.dedupeKey(), 200));
        n.setCreatedAt(now);
        n = notifications.save(n);
        log.info("Alert {} for user {}: {}", a.type(), user.getId(), a.title());
        if (a.emailIt() && user.isEmailAlerts()) {
            Long id = n.getId();
            String to = user.getEmail();
            mailer.submit(() -> {
                try {
                    if (email.sendAlert(to, "AFK Live: " + a.title(), a.title(), a.body(), a.link())) {
                        notifications.findById(id).ifPresent(saved -> {
                            saved.setEmailedAt(ZonedDateTime.now(ZoneId.of("UTC")));
                            notifications.save(saved);
                        });
                    }
                } catch (Exception e) {
                    log.warn("Alert email failed: {}", e.getMessage());
                }
            });
        }
        return Optional.of(n);
    }

    public List<Notification> latest(Long userId) {
        return notifications.findTop50ByUserIdOrderByCreatedAtDesc(userId);
    }

    public long unread(Long userId) {
        return notifications.countByUserIdAndReadAtIsNull(userId);
    }

    public void markAllRead(Long userId) {
        notifications.markAllRead(userId, ZonedDateTime.now(ZoneId.of("UTC")));
    }

    public boolean markRead(Long userId, Long id) {
        return notifications.markRead(id, userId, ZonedDateTime.now(ZoneId.of("UTC"))) == 1;
    }

    private static String trim(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    @PreDestroy
    void stop() {
        mailer.shutdown();
    }
}
