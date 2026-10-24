package com.afklive.streamer.service;

import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * GR-18: creators bring creators. Each account has an invite link (afklive.in/?ref=CODE); a sign-up
 * that arrives through one is recorded as a referral. The credit, a free month for both sides in INR,
 * is earned only after the invited creator's first <em>confirmed</em> live, so it rewards using AFK
 * Live and never a YouTube action.
 * <p>
 * <strong>Follow-up (RE-8, PR #172):</strong> "confirmed live" (the platform is receiving) lands with
 * RE-8. Until it does, referrals stay JOINED and nothing is paid: there is deliberately no payout
 * trigger here, rather than one that treats "we started sending" as confirmation. When RE-8 merges,
 * call a payout from its first-confirmation event that sets {@code confirmed_live_at} with a
 * conditional update (once), then credits both accounts.
 * <p>
 * The opt-in credit line ("Streamed 24/7 with AFK Live") is off by default, is only ever added to
 * descriptions AFK Live writes for the creator, and never appears on screen.
 */
@Service
@Slf4j
public class ReferralService {

    public static final String COOKIE = "afk_ref";
    static final Pattern CODE = Pattern.compile("[A-Za-z0-9]{6,16}");
    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"; // no look-alikes (l/1, o/0)
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final String siteUrl;

    public ReferralService(JdbcTemplate jdbc, UserRepository users, @Value("${app.base-url:https://afklive.in}") String siteUrl) {
        this.jdbc = jdbc;
        this.users = users;
        this.siteUrl = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
    }

    public static boolean validCode(String code) {
        return code != null && CODE.matcher(code).matches();
    }

    /** The account's invite code, made on first use (a conditional update, so two tabs agree on one code). */
    public String code(Long userId) {
        for (int attempt = 0; attempt < 5; attempt++) {
            String existing = jdbc.query("SELECT referral_code FROM users WHERE id = ?", rs -> rs.next() ? rs.getString(1) : null, userId);
            if (existing != null) {
                return existing;
            }
            StringBuilder c = new StringBuilder(8);
            for (int i = 0; i < 8; i++) {
                c.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
            try {
                jdbc.update("UPDATE users SET referral_code = ? WHERE id = ? AND referral_code IS NULL", c.toString(), userId);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // someone else has this code: try another
            }
        }
        throw new IllegalStateException("Could not make an invite code.");
    }

    public String link(Long userId) {
        return siteUrl + "/?ref=" + code(userId);
    }

    /** The invite code a browser arrived with (the cookie set by ReferralCookieFilter), if any. */
    public static Optional<String> codeFrom(HttpServletRequest request) {
        if (request == null || request.getCookies() == null) {
            return Optional.empty();
        }
        for (Cookie c : request.getCookies()) {
            if (COOKIE.equals(c.getName()) && validCode(c.getValue())) {
                return Optional.of(c.getValue());
            }
        }
        return Optional.empty();
    }

    /**
     * Records that a new account signed up through an invite. Ignored when the code is unknown, is the
     * account's own, or the account already has a referral. Never fails the sign-up.
     */
    public boolean recordSignup(String newUserEmail, String code) {
        try {
            if (!validCode(code)) {
                return false;
            }
            Optional<User> joined = users.findByEmail(newUserEmail);
            List<Long> referrer = jdbc.queryForList("SELECT id FROM users WHERE referral_code = ?", Long.class, code);
            if (joined.isEmpty() || referrer.isEmpty() || referrer.getFirst().equals(joined.get().getId())) {
                return false;
            }
            int n = jdbc.update("INSERT INTO referrals (referrer_id, referred_id) VALUES (?, ?) ON CONFLICT (referred_id) DO NOTHING",
                    referrer.getFirst(), joined.get().getId());
            if (n == 1) {
                log.info("Referral: user {} joined through user {}'s invite", joined.get().getId(), referrer.getFirst());
            }
            return n == 1;
        } catch (Exception e) {
            log.warn("Referral not recorded: {}", e.getMessage());
            return false;
        }
    }

    public void recordSignup(String newUserEmail, HttpServletRequest request) {
        codeFrom(request).ifPresent(code -> recordSignup(newUserEmail, code));
    }

    /** What the creator sees on the invite page. */
    public record Summary(String link, int joined, int waitingForFirstLive, int credited, boolean creditLine, String creditText,
                          boolean payoutsLive) {
    }

    public Summary summary(Long userId) {
        Map<String, Object> counts = jdbc.queryForMap("SELECT COUNT(*) AS joined, "
                + "COUNT(*) FILTER (WHERE credited_at IS NULL) AS waiting, COUNT(*) FILTER (WHERE credited_at IS NOT NULL) AS credited "
                + "FROM referrals WHERE referrer_id = ?", userId);
        String link = link(userId);
        return new Summary(link, ((Number) counts.get("joined")).intValue(), ((Number) counts.get("waiting")).intValue(),
                ((Number) counts.get("credited")).intValue(), creditLine(userId), creditText(link), false);
    }

    public boolean creditLine(Long userId) {
        Boolean on = jdbc.query("SELECT credit_line FROM users WHERE id = ?", rs -> rs.next() ? rs.getBoolean(1) : Boolean.FALSE, userId);
        return Boolean.TRUE.equals(on);
    }

    public void setCreditLine(Long userId, boolean on) {
        jdbc.update("UPDATE users SET credit_line = ? WHERE id = ?", on, userId);
    }

    public static String creditText(String link) {
        return "Streamed 24/7 with AFK Live: " + link;
    }

    /**
     * A description AFK Live is about to write for this account, with the credit line added at the end
     * when (and only when) the creator turned it on. Null stays null: we never write a description
     * just to add the line.
     */
    public String withCreditLine(Long userId, String description) {
        if (description == null || userId == null || !creditLine(userId)) {
            return description;
        }
        String line = creditText(link(userId));
        if (description.contains(line)) {
            return description;
        }
        String out = description.isBlank() ? line : description.stripTrailing() + "\n\n" + line;
        return out.length() > 5000 ? description : out; // YouTube's description limit: never cut the creator's words
    }
}
