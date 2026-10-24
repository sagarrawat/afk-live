package com.afklive.streamer.service;

import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
 * <strong>Payout:</strong> {@link FirstLiveService} records the invited creator's first confirmed live
 * (RE-8 "On"), then {@link #creditConfirmed()} moves each such referral from JOINED to CREDITED with a
 * conditional update and, in the same transaction, gives both accounts {@link #FREE_MONTH_DAYS} free
 * days through {@link BillingService#addFreeDays} and writes the audit row ({@code admin_actions},
 * action {@code REFERRAL_CREDIT}, by {@code system}). Both creators get an in-app notice after it commits.
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

    /** GR-18: "a free month for both sides" (docs/growth-plan.md), as days added to the plan. */
    public static final int FREE_MONTH_DAYS = 30;
    public static final String JOINED = "JOINED";
    public static final String CREDITED = "CREDITED";
    /** Who the audit row names for changes nobody clicked. */
    static final String SYSTEM = "system";
    public static final String NOTICE_TYPE = "REFERRAL_CREDIT";

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final String siteUrl;
    private final BillingService billing;
    private final NotificationService notifier;
    private final TransactionTemplate tx;

    public ReferralService(JdbcTemplate jdbc, UserRepository users, @Value("${app.base-url:https://afklive.in}") String siteUrl,
                           BillingService billing, NotificationService notifier, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.users = users;
        this.siteUrl = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
        this.billing = billing;
        this.notifier = notifier;
        this.tx = new TransactionTemplate(txManager);
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
                          boolean payoutsLive, int freeDaysSaved) {
    }

    public Summary summary(Long userId) {
        Map<String, Object> counts = jdbc.queryForMap("SELECT COUNT(*) AS joined, "
                + "COUNT(*) FILTER (WHERE credited_at IS NULL) AS waiting, COUNT(*) FILTER (WHERE credited_at IS NOT NULL) AS credited "
                + "FROM referrals WHERE referrer_id = ?", userId);
        String link = link(userId);
        return new Summary(link, ((Number) counts.get("joined")).intValue(), ((Number) counts.get("waiting")).intValue(),
                ((Number) counts.get("credited")).intValue(), creditLine(userId), creditText(link), true, users.bonusDays(userId));
    }

    // ------------------------------------------------------------------ payout (GR-18, after ON-8's first live confirmed)

    /** Referrals whose invited creator's first live is confirmed and that haven't been credited yet. */
    static final String DUE = "SELECT r.id FROM referrals r JOIN users joined ON joined.id = r.referred_id "
            + "JOIN users inviter ON inviter.id = r.referrer_id "
            + "WHERE r.status = 'JOINED' AND joined.first_live_at IS NOT NULL AND r.referrer_id <> r.referred_id "
            + "AND joined.enabled AND inviter.enabled ORDER BY r.id LIMIT 100";

    /** One credited referral, for the notices sent after the transaction commits. */
    record Credit(long referralId, long referrerId, long referredId, BillingService.FreeDays toReferrer, BillingService.FreeDays toReferred) {
    }

    /**
     * Credits every referral that is due. Safe to run on several instances at once: each referral is
     * claimed by one conditional update, and the credit and audit row commit with the claim or not at all.
     * Returns how many this call credited.
     */
    public int creditConfirmed() {
        int n = 0;
        for (Long id : jdbc.queryForList(DUE, Long.class)) {
            try {
                Credit c = tx.execute(status -> credit(id));
                if (c != null) {
                    n++;
                    tellBoth(c);
                }
            } catch (Exception e) {
                log.error("Referral {} not credited (will retry next sweep)", id, e);
            }
        }
        return n;
    }

    /** Claims one referral (JOINED to CREDITED) and gives both sides their free month; null if someone else did. */
    private Credit credit(long referralId) {
        List<Map<String, Object>> claimed = jdbc.queryForList("UPDATE referrals r SET status = 'CREDITED', credited_at = now(), "
                + "confirmed_live_at = u.first_live_at FROM users u WHERE r.id = ? AND r.status = 'JOINED' AND u.id = r.referred_id "
                + "AND u.first_live_at IS NOT NULL AND r.referrer_id <> r.referred_id RETURNING r.referrer_id, r.referred_id", referralId);
        if (claimed.isEmpty()) {
            return null;
        }
        long referrer = ((Number) claimed.getFirst().get("referrer_id")).longValue();
        long referred = ((Number) claimed.getFirst().get("referred_id")).longValue();
        // Always lock in id order, so two credits touching the same accounts can't deadlock.
        BillingService.FreeDays a;
        BillingService.FreeDays b;
        if (referrer < referred) {
            a = billing.addFreeDays(referrer, FREE_MONTH_DAYS);
            b = billing.addFreeDays(referred, FREE_MONTH_DAYS);
        } else {
            b = billing.addFreeDays(referred, FREE_MONTH_DAYS);
            a = billing.addFreeDays(referrer, FREE_MONTH_DAYS);
        }
        jdbc.update("INSERT INTO admin_actions (admin_email, action, target_type, target_id, detail, created_at) "
                        + "VALUES (?, 'REFERRAL_CREDIT', 'referral', ?, ?, now())", SYSTEM, String.valueOf(referralId),
                "First live confirmed for user " + referred + " (invited by user " + referrer + "): "
                        + FREE_MONTH_DAYS + " free days each. Inviter: " + outcome(a) + ". Invited: " + outcome(b) + ".");
        log.info("Referral {} credited: users {} and {} get {} free days", referralId, referrer, referred, FREE_MONTH_DAYS);
        return new Credit(referralId, referrer, referred, a, b);
    }

    private static String outcome(BillingService.FreeDays d) {
        return d.extended() ? "plan extended to " + d.until() : "saved for the next paid plan (" + d.saved() + " days waiting)";
    }

    /** In plain words, what happened to a creator's free month. */
    static String outcomeForCreator(BillingService.FreeDays d) {
        return d.extended() ? "We added it to your plan, which now runs until " + BillingService.dayOf(d.until()) + "."
                : "It's saved on your account and added when you start a paid plan.";
    }

    private void tellBoth(Credit c) {
        users.findById(c.referrerId()).ifPresent(u -> safely(() -> notifier.notify(u, new NotificationService.Alert(NOTICE_TYPE,
                com.afklive.streamer.model.Notification.Level.SUCCESS, null, "You earned a free month",
                "A creator you invited just had their first live on AFK Live. " + outcomeForCreator(c.toReferrer()),
                "/settings/invite", "referral:" + c.referralId() + ":inviter", java.time.Duration.ofDays(3650), true))));
        users.findById(c.referredId()).ifPresent(u -> safely(() -> notifier.notify(u, new NotificationService.Alert(NOTICE_TYPE,
                com.afklive.streamer.model.Notification.Level.SUCCESS, null, "Your first live worked: here's a free month",
                "You joined through an invite, so you and the creator who invited you each get a free month. " + outcomeForCreator(c.toReferred()),
                "/settings/billing", "referral:" + c.referralId() + ":invited", java.time.Duration.ofDays(3650), true))));
    }

    private static void safely(Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            log.warn("Referral notice not sent: {}", e.getMessage());
        }
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
