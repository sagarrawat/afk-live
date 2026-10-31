package com.afklive.streamer.service;

import com.afklive.streamer.model.Notification;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.util.AppConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;

/**
 * FinOps for AI (AI-1, GR-10): every model call's input and output tokens, as the API reports them, are added
 * to this UTC month's row in {@code ai_spend} and priced with per-million-token rates. Once the month's spend
 * reaches {@code APP_AI_MONTHLY_BUDGET_USD} (default $10) nothing calls AI until the next UTC month: the
 * title writer then says it isn't available, exactly as without a key. Admins get one alert at 80% and one
 * at 100% per month; the {@code ai_budget_alerts (month, threshold)} key is the claim, so with two web
 * instances only the one whose insert lands sends it (the {@link CostMeter} pattern).
 * <p>
 * The cap is checked before each call, so calls already in flight when it's reached can overshoot it by a
 * few cents. The spend is counted in the database, never only in memory, so every web instance sees it.
 */
@Service
@Slf4j
public class AiBudget {

    public static final int[] THRESHOLDS = {80, 100};
    public static final String AI_BUDGET = "AI_BUDGET";
    static final double DEFAULT_BUDGET_USD = 10;
    /** Claude Haiku 5.5's list price per million tokens, in US dollars. */
    static final double DEFAULT_INPUT_PER_MILLION = 0.10;
    static final double DEFAULT_OUTPUT_PER_MILLION = 0.50;

    private final JdbcTemplate jdbc;
    private final NotificationService notifications;
    private final UserRepository users;
    private final double budgetUsd;
    private final double inputPerMillion;
    private final double outputPerMillion;
    private Clock clock = Clock.systemUTC();

    public AiBudget(JdbcTemplate jdbc, NotificationService notifications, UserRepository users,
                    @Value("${app.ai.monthly-budget-usd:}") String budgetUsd,
                    @Value("${app.ai.price-input-per-million:}") String inputPerMillion,
                    @Value("${app.ai.price-output-per-million:}") String outputPerMillion) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.users = users;
        this.budgetUsd = number("APP_AI_MONTHLY_BUDGET_USD", budgetUsd, DEFAULT_BUDGET_USD);
        this.inputPerMillion = number("APP_AI_PRICE_INPUT_PER_MTOK", inputPerMillion, DEFAULT_INPUT_PER_MILLION);
        this.outputPerMillion = number("APP_AI_PRICE_OUTPUT_PER_MTOK", outputPerMillion, DEFAULT_OUTPUT_PER_MILLION);
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** A number of zero or more; empty or unreadable uses the default (with a warning for unreadable). */
    static double number(String name, String raw, double fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            double v = Double.parseDouble(raw.strip());
            if (Double.isFinite(v) && v >= 0) {
                return v;
            }
        } catch (NumberFormatException ignored) {
            // falls through to the warning
        }
        log.warn("{} isn't a number of zero or more; using the default {}", name, fallback);
        return fallback;
    }

    public double budgetUsd() {
        return budgetUsd;
    }

    /** What {@code in} and {@code out} tokens cost at the configured rates, in US dollars. */
    public double cost(long in, long out) {
        return in * inputPerMillion / 1_000_000d + out * outputPerMillion / 1_000_000d;
    }

    String month() {
        return BillingService.month(ZonedDateTime.now(clock.withZone(ZoneOffset.UTC)));
    }

    /** This UTC month's spend so far, in US dollars (all providers and models). */
    public double spentUsd() {
        Double v = jdbc.queryForObject("SELECT COALESCE(SUM(cost_usd), 0) FROM ai_spend WHERE month = ?", Double.class, month());
        return v == null ? 0 : v;
    }

    /** False once this month's spend has reached the cap (a cap of 0 stops AI altogether). */
    public boolean allows() {
        try {
            return spentUsd() < budgetUsd;
        } catch (Exception e) {
            // Without the database we can't count, so we don't spend.
            log.warn("AI budget unreadable: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Adds one call's tokens to this month and alerts admins when the spend passed 80% or 100% for the first
     * time this month. Never throws: a failed count must not lose the creator's draft.
     */
    public void record(String provider, String model, long in, long out) {
        try {
            String m = month();
            jdbc.update("""
                    INSERT INTO ai_spend (month, provider, model, calls, input_tokens, output_tokens, cost_usd)
                    VALUES (?, ?, ?, 1, ?, ?, ?)
                    ON CONFLICT (month, provider, model) DO UPDATE SET calls = ai_spend.calls + 1,
                        input_tokens = ai_spend.input_tokens + EXCLUDED.input_tokens,
                        output_tokens = ai_spend.output_tokens + EXCLUDED.output_tokens,
                        cost_usd = ai_spend.cost_usd + EXCLUDED.cost_usd
                    """, m, provider, cut(model, 80), in, out, cost(in, out));
            alertIfCrossed(m, spentUsd());
        } catch (Exception e) {
            log.warn("AI usage not counted: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * Claims every threshold this month's spend has passed and alerts admins about the highest one this call
     * claimed. Returns it, or null when there was nothing new.
     */
    public Integer alertIfCrossed(String month, double spent) {
        if (budgetUsd <= 0) {
            return null;
        }
        double pct = spent / budgetUsd * 100;
        Integer claimed = null;
        for (int t : THRESHOLDS) {
            if (pct >= t && claim(month, t, spent)) {
                claimed = t;
            }
        }
        if (claimed == null) {
            return null;
        }
        boolean stopped = claimed >= 100;
        String title = stopped
                ? "AI writing is paused until next month: the AI budget is used up"
                : String.format(Locale.ROOT, "AI spend is at %.0f%% of this month's budget", Math.floor(pct));
        String body = String.format(Locale.ROOT, "$%.2f of $%.2f spent in %s. %s Raise APP_AI_MONTHLY_BUDGET_USD to allow more. "
                        + "See AI spend in admin, Billing.", spent, budgetUsd, month,
                stopped ? "The title writer says it isn't available until the month ends (UTC)."
                        : "At 100% AI writing stops until the month ends (UTC).");
        Notification.Level level = stopped ? Notification.Level.ERROR : Notification.Level.WARNING;
        for (User admin : users.findAllByRole(AppConstants.ROLE_ADMIN)) {
            if (!admin.isEnabled()) {
                continue;
            }
            try {
                notifications.notify(admin, new NotificationService.Alert(AI_BUDGET, level, null, title, body,
                        "/studio/admin/billing", "ai-budget:" + month + ":" + claimed, Duration.ofDays(40), true));
            } catch (Exception e) {
                log.warn("AI budget alert for admin {} failed: {}", admin.getId(), e.getMessage());
            }
        }
        log.info("AI budget: ${} of ${} in {} ({}% threshold)", String.format(Locale.ROOT, "%.2f", spent), budgetUsd, month, claimed);
        return claimed;
    }

    boolean claim(String month, int threshold, double spent) {
        try {
            return jdbc.update("INSERT INTO ai_budget_alerts (month, threshold, spent_usd, budget_usd) VALUES (?, ?, ?, ?) "
                    + "ON CONFLICT (month, threshold) DO NOTHING", month, threshold, spent, budgetUsd) == 1;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ admin

    public record Row(String provider, String model, long calls, long inputTokens, long outputTokens, double costUsd) {
    }

    /**
     * This month's AI spend for admin Billing.
     *
     * @param provider the provider in use ("anthropic", "gemini") or null when AI is off
     */
    public record Report(String month, String provider, String model, double spentUsd, double budgetUsd, Double percent,
                         boolean stopped, double inputPerMillion, double outputPerMillion, List<Row> rows) {
    }

    public Report report(String provider, String model) {
        String m = month();
        List<Row> rows = jdbc.query("SELECT provider, model, calls, input_tokens, output_tokens, cost_usd FROM ai_spend "
                        + "WHERE month = ? ORDER BY cost_usd DESC, provider, model",
                (rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getDouble(6)), m);
        double spent = rows.stream().mapToDouble(Row::costUsd).sum();
        return new Report(m, provider, model, spent, budgetUsd, budgetUsd > 0 ? spent / budgetUsd * 100 : null,
                spent >= budgetUsd, inputPerMillion, outputPerMillion, rows);
    }

    private static String cut(String s, int max) {
        String t = s == null ? "" : s;
        return t.length() > max ? t.substring(0, max) : t;
    }
}
