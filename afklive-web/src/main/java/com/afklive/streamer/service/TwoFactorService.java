package com.afklive.streamer.service;

import com.afklive.streamer.exception.ConflictException;
import com.afklive.streamer.exception.NotFoundException;
import com.afklive.streamer.exception.TooManyAttemptsException;
import com.afklive.streamer.model.RecoveryCode;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.RecoveryCodeRepository;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.security.Totp;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * ON-6: two-step sign-in with an authenticator app. Set up (a pending secret, confirmed with a code),
 * ten one-time recovery codes shown once (only hashes are kept), sign-in checks, and turning it off
 * (needs a current code). Wrong codes are counted in the database, so the lockout holds across
 * instances: {@value #MAX_FAILURES} in a row lock code entry for {@link #LOCKOUT}.
 */
@Service
public class TwoFactorService {

    public static final String ISSUER = "AFK Live";
    static final int MAX_FAILURES = 5;
    static final Duration LOCKOUT = Duration.ofMinutes(10);
    static final int RECOVERY_CODES = 10;
    private static final String RECOVERY_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final RecoveryCodeRepository recoveryCodes;
    private final TransactionTemplate tx;
    private Clock clock = Clock.systemUTC();

    public TwoFactorService(UserRepository users, RecoveryCodeRepository recoveryCodes, PlatformTransactionManager txManager) {
        this.users = users;
        this.recoveryCodes = recoveryCodes;
        this.tx = new TransactionTemplate(txManager);
    }

    /** For tests: a fixed time for TOTP checks. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    public record Status(boolean enabled, long recoveryCodesLeft, ZonedDateTime enabledAt) {
    }

    public record Setup(String secret, String uri) {
    }

    private enum Outcome { OK, WRONG, LOCKED }

    private record Check(Outcome outcome, long retryAfterSeconds) {
    }

    public Status status(String email) {
        User u = user(email);
        return new Status(u.isTwoFactorEnabled(), u.isTwoFactorEnabled() ? recoveryCodes.countByUserIdAndUsedAtIsNull(u.getId()) : 0,
                u.getTwoFactorEnabledAt());
    }

    /** True if signing in as this account needs a code. */
    public boolean required(String email) {
        return users.findByEmail(email).map(User::isTwoFactorEnabled).orElse(false);
    }

    /** Starts setting up: a new secret the app scans; nothing changes until {@link #enable} confirms it. */
    public Setup beginSetup(String email) {
        return tx.execute(s -> {
            User u = users.lockByEmail(email).orElseThrow(() -> new NotFoundException("Account not found."));
            if (u.isTwoFactorEnabled()) {
                throw new ConflictException("Two-step sign-in is already on.");
            }
            String secret = Totp.newSecret();
            u.setTwoFactorPendingSecret(secret);
            users.save(u);
            return new Setup(secret, Totp.uri(ISSUER, u.getEmail(), secret));
        });
    }

    /** The otpauth:// link for the secret being set up (for the QR code). */
    public String pendingUri(String email) {
        User u = user(email);
        if (u.getTwoFactorPendingSecret() == null) {
            throw new NotFoundException("Start setting up two-step sign-in first.");
        }
        return Totp.uri(ISSUER, u.getEmail(), u.getTwoFactorPendingSecret());
    }

    /** Confirms setup with a code from the app; returns the recovery codes (shown once). */
    public List<String> enable(String email, String code) {
        List<String> plain = new ArrayList<>();
        Check result = tx.execute(s -> {
            User u = users.lockByEmail(email).orElseThrow(() -> new NotFoundException("Account not found."));
            if (u.isTwoFactorEnabled()) {
                throw new ConflictException("Two-step sign-in is already on.");
            }
            if (u.getTwoFactorPendingSecret() == null) {
                throw new IllegalArgumentException("Start setting up two-step sign-in first.");
            }
            Check c = check(u, code, u.getTwoFactorPendingSecret(), false);
            if (c.outcome() == Outcome.OK) {
                u.setTwoFactorSecret(u.getTwoFactorPendingSecret());
                u.setTwoFactorPendingSecret(null);
                u.setTwoFactorEnabledAt(now());
                plain.addAll(replaceRecoveryCodes(u.getId()));
            }
            users.save(u);
            return c;
        });
        raise(result, "That code didn't match. Check the time on your phone and enter the newest code.");
        return plain;
    }

    /** Turns two-step sign-in off; needs a current code (or a recovery code). */
    public void disable(String email, String code) {
        Check result = tx.execute(s -> {
            User u = users.lockByEmail(email).orElseThrow(() -> new NotFoundException("Account not found."));
            if (!u.isTwoFactorEnabled()) {
                throw new ConflictException("Two-step sign-in is already off.");
            }
            Check c = check(u, code, u.getTwoFactorSecret(), true);
            if (c.outcome() == Outcome.OK) {
                u.setTwoFactorSecret(null);
                u.setTwoFactorPendingSecret(null);
                u.setTwoFactorEnabledAt(null);
                u.setTwoFactorLastStep(null);
                recoveryCodes.deleteAllForUser(u.getId());
            }
            users.save(u);
            return c;
        });
        raise(result, "That code didn't match. Enter the newest code from your authenticator app.");
    }

    /** New recovery codes (the old ones stop working); needs a current code. */
    public List<String> regenerateRecoveryCodes(String email, String code) {
        List<String> plain = new ArrayList<>();
        Check result = tx.execute(s -> {
            User u = users.lockByEmail(email).orElseThrow(() -> new NotFoundException("Account not found."));
            if (!u.isTwoFactorEnabled()) {
                throw new ConflictException("Turn on two-step sign-in first.");
            }
            Check c = check(u, code, u.getTwoFactorSecret(), false);
            if (c.outcome() == Outcome.OK) {
                plain.addAll(replaceRecoveryCodes(u.getId()));
            }
            users.save(u);
            return c;
        });
        raise(result, "That code didn't match. Enter the newest code from your authenticator app.");
        return plain;
    }

    /**
     * The second sign-in step: a code from the app or an unused recovery code. Returns false for a
     * wrong code; throws {@link TooManyAttemptsException} while locked out.
     */
    public boolean verifySignIn(String email, String code) {
        Check result = tx.execute(s -> {
            User u = users.lockByEmail(email).orElseThrow(() -> new NotFoundException("Account not found."));
            if (!u.isTwoFactorEnabled()) {
                return new Check(Outcome.OK, 0);
            }
            Check c = check(u, code, u.getTwoFactorSecret(), true);
            users.save(u);
            return c;
        });
        if (result.outcome() == Outcome.LOCKED) {
            throw locked(result.retryAfterSeconds());
        }
        return result.outcome() == Outcome.OK;
    }

    // ------------------------------------------------------------------ internals

    /** Checks a code on a locked user row and updates the failure count and last used step. */
    private Check check(User u, String code, String secret, boolean allowRecovery) {
        ZonedDateTime now = now();
        if (u.getTwoFactorLockedUntil() != null && u.getTwoFactorLockedUntil().isAfter(now)) {
            return new Check(Outcome.LOCKED, Duration.between(now, u.getTwoFactorLockedUntil()).toSeconds() + 1);
        }
        boolean ok = false;
        String normalized = code == null ? "" : code.replaceAll("[\\s-]", "").toLowerCase(Locale.ROOT);
        if (normalized.length() == Totp.DIGITS && normalized.chars().allMatch(Character::isDigit)) {
            long step = Totp.verify(secret, normalized, clock.instant().getEpochSecond(), u.getTwoFactorLastStep());
            if (step >= 0) {
                u.setTwoFactorLastStep(step);
                ok = true;
            }
        } else if (allowRecovery && normalized.length() == 10) {
            ok = recoveryCodes.use(u.getId(), hash(normalized), now) == 1;
        }
        if (ok) {
            u.setTwoFactorFailures(0);
            u.setTwoFactorLockedUntil(null);
            return new Check(Outcome.OK, 0);
        }
        int failures = u.getTwoFactorFailures() + 1;
        if (failures >= MAX_FAILURES) {
            u.setTwoFactorFailures(0);
            u.setTwoFactorLockedUntil(now.plus(LOCKOUT));
            return new Check(Outcome.LOCKED, LOCKOUT.toSeconds());
        }
        u.setTwoFactorFailures(failures);
        return new Check(Outcome.WRONG, 0);
    }

    private void raise(Check result, String wrongMessage) {
        if (result.outcome() == Outcome.LOCKED) {
            throw locked(result.retryAfterSeconds());
        }
        if (result.outcome() == Outcome.WRONG) {
            throw new IllegalArgumentException(wrongMessage);
        }
    }

    private static TooManyAttemptsException locked(long seconds) {
        long minutes = Math.max(1, (seconds + 59) / 60);
        return new TooManyAttemptsException("Too many wrong codes. Try again in " + minutes
                + (minutes == 1 ? " minute." : " minutes."), seconds);
    }

    private List<String> replaceRecoveryCodes(Long userId) {
        recoveryCodes.deleteAllForUser(userId);
        List<String> plain = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            StringBuilder sb = new StringBuilder(10);
            for (int j = 0; j < 10; j++) {
                sb.append(RECOVERY_ALPHABET.charAt(RANDOM.nextInt(RECOVERY_ALPHABET.length())));
            }
            String code = sb.toString();
            recoveryCodes.save(new RecoveryCode(userId, hash(code)));
            plain.add(code.substring(0, 5) + "-" + code.substring(5));
        }
        return plain;
    }

    static String hash(String normalizedCode) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalizedCode.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ZonedDateTime now() {
        return ZonedDateTime.ofInstant(clock.instant(), ZoneId.of("UTC"));
    }

    private User user(String email) {
        return users.findByEmail(email).orElseThrow(() -> new NotFoundException("Account not found."));
    }
}
