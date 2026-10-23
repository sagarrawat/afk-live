package com.afklive.streamer.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * What a signed-in session may do right now (ON-6, ON-7): a session of an account with two-step
 * sign-in is only partly signed in until the code is entered ({@link #VERIFIED} in the session),
 * and an account scheduled for deletion can't be used at all. Read from the database on every
 * request, so a change on one instance applies to every session everywhere.
 */
@Component
public class AccountGate {

    /** Session attribute: the email whose second sign-in step this session passed. */
    public static final String VERIFIED = "AFK_2FA_VERIFIED";
    /** Session attribute: the account (scheduled for deletion) that this browser proved it owns, and may restore. */
    public static final String RESTORE = "AFK_RESTORE_EMAIL";

    private final JdbcTemplate jdbc;

    public AccountGate(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record State(boolean twoFactor, ZonedDateTime deletionScheduledFor) {
    }

    public Optional<State> state(String email) {
        List<State> rows = jdbc.query("SELECT two_factor_secret IS NOT NULL AS tf, deletion_scheduled_for FROM users WHERE email = ?",
                (rs, i) -> {
                    Timestamp t = rs.getTimestamp("deletion_scheduled_for");
                    return new State(rs.getBoolean("tf"), t == null ? null : t.toInstant().atZone(ZoneId.of("UTC")));
                }, email);
        return rows.stream().findFirst();
    }
}
