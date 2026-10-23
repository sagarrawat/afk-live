package com.afklive.streamer.service;

import com.afklive.streamer.model.PushSubscription;
import com.afklive.streamer.repository.PushSubscriptionRepository;
import com.afklive.streamer.util.WebPush;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Alerts as push notifications on the creator's phone or computer (NT-1, MB-2), straight to the
 * browser's push service with VAPID. Off unless {@code APP_PUSH_VAPID_KEY} is set (see
 * docs/operations.md); then every alert that is also emailed is pushed too.
 */
@Slf4j
@Service
public class PushService {

    /** Push services browsers use; nothing else is ever called (no server-side request forgery). */
    private static final List<String> PUSH_HOSTS = List.of("fcm.googleapis.com", "updates.push.services.mozilla.com",
            "push.services.mozilla.com", "web.push.apple.com", "notify.windows.com", "push.apple.com");
    static final int MAX_PER_USER = 10;

    private final PushSubscriptionRepository subscriptions;
    private final KeyPair vapid;
    private final String subject;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();

    public PushService(PushSubscriptionRepository subscriptions, @Value("${app.push.vapid-key:}") String vapidKey,
                       @Value("${app.email.support:support@afklive.in}") String support) {
        this.subscriptions = subscriptions;
        this.subject = "mailto:" + support;
        KeyPair kp = null;
        if (vapidKey != null && !vapidKey.isBlank()) {
            try {
                kp = WebPush.fromSec1(Base64.getMimeDecoder().decode(vapidKey.trim()));
            } catch (Exception e) {
                log.error("APP_PUSH_VAPID_KEY can't be read; push alerts are off: {}", e.getMessage());
            }
        }
        this.vapid = kp;
    }

    public boolean enabled() {
        return vapid != null;
    }

    /** For the browser's subscribe call (applicationServerKey), base64url. */
    public String publicKey() {
        return vapid == null ? null : Base64.getUrlEncoder().withoutPadding().encodeToString(WebPush.raw((ECPublicKey) vapid.getPublic()));
    }

    public PushSubscription subscribe(Long userId, String endpoint, String p256dh, String auth, String userAgent) {
        if (!enabled()) {
            throw new IllegalStateException("Push alerts aren't set up on this server.");
        }
        URI uri = validEndpoint(endpoint);
        byte[] key = decode(p256dh, "key");
        byte[] secret = decode(auth, "auth secret");
        if (key.length != 65 || key[0] != 4 || secret.length != 16) {
            throw new IllegalArgumentException("That browser subscription isn't valid.");
        }
        PushSubscription s = subscriptions.findByEndpoint(uri.toString()).orElseGet(PushSubscription::new);
        if (s.getId() == null && subscriptions.countByUserId(userId) >= MAX_PER_USER) {
            throw new IllegalArgumentException("Alerts are on for " + MAX_PER_USER + " devices already. Turn them off on one you no longer use.");
        }
        s.setUserId(userId); // an endpoint belongs to whoever signed in on that browser last
        s.setEndpoint(uri.toString());
        s.setP256dh(p256dh);
        s.setAuth(auth);
        s.setUserAgent(userAgent == null ? null : userAgent.length() > 200 ? userAgent.substring(0, 200) : userAgent);
        if (s.getCreatedAt() == null) {
            s.setCreatedAt(ZonedDateTime.now(ZoneId.of("UTC")));
        }
        return subscriptions.save(s);
    }

    public void unsubscribe(Long userId, String endpoint) {
        subscriptions.deleteByEndpointAndUserId(endpoint, userId);
    }

    public long devices(Long userId) {
        return subscriptions.countByUserId(userId);
    }

    /** Sends to every device of this user; drops subscriptions the push service says are gone. Never throws. */
    public int send(Long userId, String title, String body, String url, String tag) {
        if (!enabled()) {
            return 0;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("body", body == null ? "" : body.length() > 400 ? body.substring(0, 399) + "…" : body);
        m.put("url", url == null ? "/studio" : url);
        m.put("tag", tag);
        byte[] payload;
        try {
            payload = json.writeValueAsBytes(m);
        } catch (Exception e) {
            return 0;
        }
        int sent = 0;
        for (PushSubscription s : subscriptions.findAllByUserId(userId)) {
            try {
                URI uri = validEndpoint(s.getEndpoint());
                byte[] encrypted = WebPush.encrypt(payload, decode(s.getP256dh(), "key"), decode(s.getAuth(), "auth"));
                String audience = uri.getScheme() + "://" + uri.getHost();
                HttpRequest req = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(15))
                        .header("TTL", "86400")
                        .header("Urgency", "high")
                        .header("Content-Encoding", "aes128gcm")
                        .header("Content-Type", "application/octet-stream")
                        .header("Authorization", WebPush.vapidAuthorization(vapid, audience, subject, Instant.now().plus(Duration.ofHours(12)).getEpochSecond()))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(encrypted))
                        .build();
                int code = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
                if (code == 404 || code == 410) {
                    subscriptions.delete(s); // the browser unsubscribed or the app was removed
                } else if (code >= 200 && code < 300) {
                    s.setLastSentAt(ZonedDateTime.now(ZoneId.of("UTC")));
                    subscriptions.save(s);
                    sent++;
                } else {
                    log.warn("Push to {} answered {}", uri.getHost(), code);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Push failed: {}", e.getMessage());
            }
        }
        return sent;
    }

    static URI validEndpoint(String endpoint) {
        URI uri;
        try {
            uri = URI.create(endpoint == null ? "" : endpoint.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("That browser subscription isn't valid.");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean known = PUSH_HOSTS.stream().anyMatch(h -> host.equals(h) || host.endsWith("." + h));
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !known || uri.getPort() != -1 && uri.getPort() != 443) {
            throw new IllegalArgumentException("That browser's push service isn't supported.");
        }
        return uri;
    }

    private static byte[] decode(String v, String what) {
        try {
            return Base64.getUrlDecoder().decode(v == null ? "" : v.trim().replace('+', '-').replace('/', '_').replace("=", ""));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("That browser subscription's " + what + " isn't valid.");
        }
    }
}
