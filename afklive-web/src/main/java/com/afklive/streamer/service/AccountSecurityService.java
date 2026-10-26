package com.afklive.streamer.service;

import com.afklive.streamer.exception.ConflictException;
import com.afklive.streamer.exception.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * ON-6: what a signed-in creator can do about their own sign-in: change the password (needs the current
 * one), change the email (needs the password and a link sent to the new address), and sign out every
 * other device.
 * <p>
 * Sign-ins live in two places: the server sessions (Spring Session JDBC, indexed by the principal's name,
 * which is the email for both password and Google sign-in) and "keep me signed in" cookies. Signing out
 * other devices deletes the other sessions and raises {@code sessions_version}, which is part of every
 * remember-me cookie's signature (see {@link #rememberMePassword}), so the cookies stop working too.
 */
@Service
@Slf4j
public class AccountSecurityService {

    /** The same rule as sign-up. */
    public static final Pattern PASSWORD_RULE = Pattern.compile("^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=.*[@#$%^&+=!_\\-]).{8,}$");
    public static final String PASSWORD_RULE_TEXT = "Use at least 8 characters with an uppercase letter, a lowercase letter, a number and a symbol (like ! or @).";
    static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    static final Duration EMAIL_LINK_VALID = Duration.ofHours(24);

    public static final String EMAIL_OFF = "Changing your email isn't available yet: we can't send the confirmation email right now. "
            + "Write to support if you need it changed.";
    public static final String NO_PASSWORD = "Your account signs in with Google, so it has no AFK Live password.";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final EmailService email;
    private final ObjectProvider<FindByIndexNameSessionRepository<? extends Session>> sessions;
    private final SecureRandom random = new SecureRandom();

    public AccountSecurityService(JdbcTemplate jdbc, PasswordEncoder encoder, EmailService email,
                                  ObjectProvider<FindByIndexNameSessionRepository<? extends Session>> sessions) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.email = email;
        this.sessions = sessions;
    }

    /** What Settings > Sign-in & security shows. */
    public record Status(boolean hasPassword, boolean emailChangeAvailable, String pendingEmail) {
    }

    private record Row(long id, String email, String password, int sessionsVersion, String pendingEmail) {
    }

    private Optional<Row> row(String accountEmail) {
        return jdbc.query("SELECT id, email, password, sessions_version, pending_email FROM users WHERE email = ?",
                (rs, i) -> new Row(rs.getLong("id"), rs.getString("email"), rs.getString("password"), rs.getInt("sessions_version"),
                        rs.getString("pending_email")), accountEmail).stream().findFirst();
    }

    private Row require(String accountEmail) {
        return row(accountEmail).orElseThrow(() -> new NotFoundException("Account not found."));
    }

    public Status status(String accountEmail) {
        Row r = require(accountEmail);
        return new Status(r.password() != null, email.isConfigured(), r.pendingEmail());
    }

    /**
     * What a remember-me cookie is signed with: the stored password hash (or the Google-only placeholder),
     * plus the sessions version once it has been raised. At version 0 it is exactly the old value, so
     * cookies issued before this release keep working.
     */
    public static String rememberMePassword(String password, int sessionsVersion) {
        return sessionsVersion <= 0 ? password : password + "|v" + sessionsVersion;
    }

    public int sessionsVersion(String accountEmail) {
        return row(accountEmail).map(Row::sessionsVersion).orElse(0);
    }

    // ------------------------------------------------------------------ password

    /** Changes the password and signs out every other device; returns how many other sessions ended. */
    @Transactional
    public int changePassword(String accountEmail, String currentPassword, String newPassword, String currentSessionId) {
        Row r = require(accountEmail);
        checkPassword(r, currentPassword);
        if (newPassword == null || !PASSWORD_RULE.matcher(newPassword).matches()) {
            throw new IllegalArgumentException(PASSWORD_RULE_TEXT);
        }
        if (encoder.matches(newPassword, r.password())) {
            throw new IllegalArgumentException("That's your current password. Choose a new one.");
        }
        // A new hash changes every remember-me signature; raising the version as well keeps one rule for both.
        jdbc.update("UPDATE users SET password = ?, reset_token = NULL, sessions_version = sessions_version + 1 WHERE id = ?",
                encoder.encode(newPassword), r.id());
        log.info("User {} changed their password", r.id());
        return endSessions(r.email(), currentSessionId);
    }

    private void checkPassword(Row r, String given) {
        if (r.password() == null) {
            throw new IllegalStateException(NO_PASSWORD);
        }
        if (given == null || given.isEmpty() || !encoder.matches(given, r.password())) {
            throw new IllegalArgumentException("That isn't your current password.");
        }
    }

    // ------------------------------------------------------------------ other devices

    /** Ends every session of the account except {@code currentSessionId}, and every remember-me cookie. */
    @Transactional
    public int signOutOthers(String accountEmail, String currentSessionId) {
        Row r = require(accountEmail);
        jdbc.update("UPDATE users SET sessions_version = sessions_version + 1 WHERE id = ?", r.id());
        int ended = endSessions(r.email(), currentSessionId);
        log.info("User {} signed out {} other session(s)", r.id(), ended);
        return ended;
    }

    /** Deletes the account's sessions, keeping {@code keepId} (null: none). */
    int endSessions(String principal, String keepId) {
        FindByIndexNameSessionRepository<? extends Session> repo = sessions.getIfAvailable();
        if (repo == null) {
            return 0;
        }
        Map<String, ? extends Session> found = repo.findByPrincipalName(principal);
        int ended = 0;
        for (String id : List.copyOf(found.keySet())) {
            if (!id.equals(keepId)) {
                repo.deleteById(id);
                ended++;
            }
        }
        return ended;
    }

    // ------------------------------------------------------------------ email

    /**
     * Starts an email change: checks the password, then mails a link to the new address (and a note to the
     * old one). Nothing changes until the link is opened.
     */
    @Transactional
    public String requestEmailChange(String accountEmail, String newEmail, String password, String baseUrl) {
        if (!email.isConfigured()) {
            throw new ConflictException(EMAIL_OFF);
        }
        Row r = require(accountEmail);
        checkPassword(r, password);
        String next = newEmail == null ? "" : newEmail.trim().toLowerCase(Locale.ROOT);
        if (next.length() > 254 || !EMAIL.matcher(next).matches()) {
            throw new IllegalArgumentException("Enter a full email address, like name@example.com.");
        }
        if (next.equalsIgnoreCase(r.email())) {
            throw new IllegalArgumentException("That's already your email.");
        }
        if (taken(next, r.id())) {
            throw new ConflictException("That address already has an AFK Live account.");
        }
        String token = token();
        jdbc.update("UPDATE users SET pending_email = ?, email_change_token_hash = ?, email_change_expires_at = ? WHERE id = ?",
                next, hash(token), Timestamp.from(Instant.now().plus(EMAIL_LINK_VALID)), r.id());
        email.sendEmailChangeLink(next, baseUrl + "/account/confirm-email?token=" + token);
        email.sendEmailChangeNotice(r.email(), next);
        log.info("User {} asked to change their email", r.id());
        return next;
    }

    public void cancelEmailChange(String accountEmail) {
        jdbc.update("UPDATE users SET pending_email = NULL, email_change_token_hash = NULL, email_change_expires_at = NULL WHERE email = ?",
                accountEmail);
    }

    /** What happened to a confirmation link. */
    public record Confirmed(String oldEmail, String newEmail) {
    }

    /**
     * Opens a confirmation link: the account's email becomes the new address and every sign-in of the
     * account ends (sessions are filed under the old address). Empty when the link is unknown or expired.
     */
    @Transactional
    public Optional<Confirmed> confirmEmailChange(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        List<Row> rows = jdbc.query("SELECT id, email, password, sessions_version, pending_email FROM users "
                        + "WHERE email_change_token_hash = ? AND email_change_expires_at > now() FOR UPDATE",
                (rs, i) -> new Row(rs.getLong("id"), rs.getString("email"), rs.getString("password"), rs.getInt("sessions_version"),
                        rs.getString("pending_email")), hash(token));
        if (rows.isEmpty() || rows.get(0).pendingEmail() == null) {
            return Optional.empty();
        }
        Row r = rows.get(0);
        if (taken(r.pendingEmail(), r.id())) {
            throw new ConflictException("That address already has an AFK Live account, so your email wasn't changed.");
        }
        jdbc.update("UPDATE users SET email = pending_email, pending_email = NULL, email_change_token_hash = NULL, "
                + "email_change_expires_at = NULL, sessions_version = sessions_version + 1 WHERE id = ?", r.id());
        endSessions(r.email(), null);
        log.info("User {} confirmed a new email", r.id());
        return Optional.of(new Confirmed(r.email(), r.pendingEmail()));
    }

    private boolean taken(String address, long exceptId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM users WHERE lower(email) = lower(?) AND id <> ?", Integer.class, address, exceptId);
        return n != null && n > 0;
    }

    private String token() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
