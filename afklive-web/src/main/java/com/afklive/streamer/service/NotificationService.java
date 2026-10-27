package com.afklive.streamer.service;

import com.afklive.streamer.model.Notification;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.NotificationRepository;
import com.afklive.streamer.repository.UserRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
 * <p>
 * OPS-4: several web instances run the same checks (two during every deploy), so "sent within the
 * window?" and the insert happen under a transaction-scoped lock on the user and key: when two
 * instances raise the same alert at the same moment, the second waits, sees the first, and skips.
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
        /** RE-10: the run has been slower than real time for a minute; viewers see slow playback. */
        public static final String STREAM_SLOW = "STREAM_SLOW";
        public static final String STREAM_SPEED_OK = "STREAM_SPEED_OK";
        /** FO-5: the account passed 80%, 100% or 110% of this month's streaming data. */
        public static final String STREAMING_DATA = "STREAMING_DATA";

        private Types() {
        }
    }

    private final NotificationRepository notifications;
    private final UserRepository users;
    private final EmailService email;
    private final PushService push;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ExecutorService mailer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "alert-mailer");
        t.setDaemon(true);
        return t;
    });

    /** Scopes the advisory locks below to alerts (first key of pg_advisory_xact_lock(int, int)). */
    private static final int DEDUPE_LOCK_SPACE = 0x4146_4b4e; // "AFKN"

    public NotificationService(NotificationRepository notifications, UserRepository users, EmailService email, PushService push,
                               JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.notifications = notifications;
        this.users = users;
        this.email = email;
        this.push = push;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
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
        Notification stored = tx.execute(status -> store(user, a, now));
        if (stored == null) {
            return Optional.empty();
        }
        deliver(user, a, stored);
        return Optional.of(stored);
    }

    /** Inserts the alert unless the same key was stored within its window; null when it was. */
    private Notification store(User user, Alert a, ZonedDateTime now) {
        boolean deduped = a.dedupeKey() != null && a.dedupeWindow() != null;
        if (deduped) {
            // Held until this transaction ends: a second instance raising the same alert waits here, then sees this row.
            jdbc.query("SELECT pg_advisory_xact_lock(?, hashtext(?))", rs -> null,
                    DEDUPE_LOCK_SPACE, user.getId() + ":" + trim(a.dedupeKey(), 200));
            if (notifications.existsByUserIdAndDedupeKeyAndCreatedAtAfter(user.getId(), trim(a.dedupeKey(), 200),
                    now.minus(a.dedupeWindow()))) {
                return null;
            }
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
        return notifications.saveAndFlush(n);
    }

    private void deliver(User user, Alert a, Notification n) {
        log.info("Alert {} for user {}: {}", a.type(), user.getId(), a.title());
        AlertPreferences.Channels channels = AlertPreferences.forType(user.getAlertPreferences(), a.type());
        if (a.emailIt() && user.isEmailAlerts() && channels.email()) {
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
        if (a.emailIt() && push.enabled() && channels.push()) {
            // NT-1 / MB-2: the same alerts on the creator's devices; links open the app.
            Long userId = user.getId();
            String url = a.link() == null ? "/studio" : a.link().startsWith("/studio") ? a.link() : "/studio" + a.link();
            mailer.submit(() -> push.send(userId, a.title(), a.body(), url, a.dedupeKey() != null ? a.dedupeKey() : a.type()));
        }
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
