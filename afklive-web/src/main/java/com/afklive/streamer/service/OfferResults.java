package com.afklive.streamer.service;

import com.afklive.streamer.exception.NotFoundException;
import com.afklive.streamer.model.LaunchOffer;
import com.afklive.streamer.repository.LaunchOfferRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * BI-9: how a launch offer did, for Admin &gt; Billing. Read-only, five aggregate queries per offer whatever the number
 * of customers (offer_redemptions is keyed by account and indexed by offer; payment_audits has a partial index on
 * offer_id). Definitions:
 * <ul>
 *   <li>Redemptions: accounts that paid with it (one offer per account), by plan and cycle.</li>
 *   <li>Discount given: the sum over completed payments with this offer of what was taken off the list price, with
 *       GST (as customers saw it) and without (INR: the taxable value of the list price less that of the price paid,
 *       rounded as on the invoice; USD has no GST). Refunded payments are left out. Per currency.</li>
 *   <li>In a discounted period now: the account's plan is active and its latest plan payment used this offer.
 *       Discounted payments still to come: the account is on the same plan and cycle and hasn't used them all.</li>
 *   <li>After the offer: accounts that used every discounted payment, and of them those who then paid a renewal at the
 *       full price (or upgraded, at the list price).</li>
 *   <li>Conversion: accounts created while the offer ran (not admins), and how many of them paid with it. For a
 *       code-only offer the sign-ups include people who never saw the code.</li>
 * </ul>
 */
@Service
public class OfferResults {

    private static final String PAID = "p.status IN ('COMPLETED', 'SUCCESS')";
    private static final String PLAN_PAYMENT = PAID + " AND (p.kind IS NULL OR p.kind <> 'BALANCE')";

    private final NamedParameterJdbcTemplate jdbc;
    private final LaunchOfferRepository offers;

    public OfferResults(NamedParameterJdbcTemplate jdbc, LaunchOfferRepository offers) {
        this.jdbc = jdbc;
        this.offers = offers;
    }

    public record PlanCount(String plan, String cycle, long accounts) {
    }

    /** Minor units (paise or cents). {@code paid}: what those payments came to, with GST. */
    public record Money(String currency, long payments, long discountWithGst, long discountWithoutGst, long paid) {
    }

    public record Results(Long offerId, String name, String status, ZonedDateTime from, ZonedDateTime to, long redemptions,
                          List<PlanCount> byPlan, List<Money> discount, long inDiscountedPeriod, long discountedPaymentsToCome,
                          long finishedOffer, long renewedAtFullPrice, long upgradedAfter, long signUps, long signUpsTookOffer,
                          Double conversionPercent, long signUpsPaidAnyPlan, boolean codeOnly) {
    }

    public Results results(Long offerId, ZonedDateTime now) {
        return results(offerId, now, null);
    }

    /** {@code emailLike} limits sign-ups to matching emails (tests share a database); null: everyone. */
    Results results(Long offerId, ZonedDateTime now, String emailLike) {
        LaunchOffer o = offers.findById(offerId).orElseThrow(() -> new NotFoundException("Offer not found."));
        ZonedDateTime utc = now.withZoneSameInstant(ZoneOffset.UTC);
        ZonedDateTime to = o.closesAt().isBefore(utc) ? o.closesAt() : utc;
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("id", offerId)
                .addValue("now", utc.toOffsetDateTime())
                .addValue("from", o.getStartsAt().toOffsetDateTime())
                .addValue("to", to.toOffsetDateTime())
                .addValue("scope", emailLike);

        List<PlanCount> byPlan = jdbc.query("SELECT plan_type, billing_cycle, count(*) AS n FROM offer_redemptions WHERE offer_id = :id "
                        + "GROUP BY 1, 2 ORDER BY 1, 2", p,
                (rs, i) -> new PlanCount(rs.getString("plan_type"), rs.getString("billing_cycle"), rs.getLong("n")));
        long redemptions = byPlan.stream().mapToLong(PlanCount::accounts).sum();

        // Discount without GST, per payment as the invoice works it out: taxable(list) - taxable(list - discount).
        List<Money> money = jdbc.query("SELECT COALESCE(p.currency, 'INR') AS cur, count(*) AS n, "
                        + "COALESCE(SUM(p.discount_amount), 0) AS gross, "
                        + "COALESCE(SUM(CASE WHEN COALESCE(p.currency, 'INR') = 'INR' THEN "
                        + "ROUND(COALESCE(p.list_amount, p.amount + p.discount_amount) * 100.0 / 118) "
                        + "- ROUND((COALESCE(p.list_amount, p.amount + p.discount_amount) - p.discount_amount) * 100.0 / 118) "
                        + "ELSE p.discount_amount END), 0) AS net, COALESCE(SUM(p.amount), 0) AS paid "
                        + "FROM payment_audits p WHERE p.offer_id = :id AND p.discount_amount > 0 AND " + PAID
                        + " GROUP BY 1 ORDER BY 1", p,
                (rs, i) -> new Money(rs.getString("cur"), rs.getLong("n"), rs.getLong("gross"), rs.getLong("net"), rs.getLong("paid")));

        long[] now3 = jdbc.query("SELECT "
                + "count(*) FILTER (WHERE s.status = 'ACTIVE' AND s.current_period_end > CAST(:now AS timestamptz) AND "
                + "(SELECT p.offer_id FROM payment_audits p WHERE p.user_id = r.user_id AND " + PLAN_PAYMENT
                + " ORDER BY p.created_at DESC, p.id DESC LIMIT 1) = :id) AS in_period, "
                + "count(*) FILTER (WHERE r.periods_used < r.periods_total AND s.status IN ('ACTIVE', 'PAST_DUE') "
                + "AND s.plan_type = r.plan_type AND s.billing_cycle = r.billing_cycle) AS to_come, "
                + "count(*) FILTER (WHERE r.periods_used >= r.periods_total) AS finished "
                + "FROM offer_redemptions r LEFT JOIN subscriptions s ON s.user_id = r.user_id WHERE r.offer_id = :id", p, rs -> {
                    rs.next();
                    return new long[] {rs.getLong("in_period"), rs.getLong("to_come"), rs.getLong("finished")};
                });

        long[] after = jdbc.query("SELECT "
                + "count(DISTINCT r.user_id) FILTER (WHERE p.kind = 'RENEW') AS renewed, "
                + "count(DISTINCT r.user_id) FILTER (WHERE p.kind = 'UPGRADE') AS upgraded "
                + "FROM offer_redemptions r JOIN payment_audits p ON p.user_id = r.user_id "
                + "WHERE r.offer_id = :id AND " + PAID + " AND p.discount_amount = 0 AND p.created_at > r.created_at", p, rs -> {
                    rs.next();
                    return new long[] {rs.getLong("renewed"), rs.getLong("upgraded")};
                });

        long[] conv = jdbc.query("WITH su AS (SELECT u.id FROM users u WHERE u.created_at >= CAST(:from AS timestamptz) "
                + "AND u.created_at < CAST(:to AS timestamptz) AND COALESCE(u.role, 'ROLE_USER') <> 'ROLE_ADMIN' "
                + "AND (CAST(:scope AS text) IS NULL OR u.email LIKE CAST(:scope AS text))) "
                + "SELECT count(*) AS signups, "
                + "count(*) FILTER (WHERE EXISTS (SELECT 1 FROM offer_redemptions r WHERE r.user_id = su.id AND r.offer_id = :id)) AS took, "
                + "count(*) FILTER (WHERE EXISTS (SELECT 1 FROM payment_audits p WHERE p.user_id = su.id AND " + PLAN_PAYMENT + ")) AS paid "
                + "FROM su", p, rs -> {
                    rs.next();
                    return new long[] {rs.getLong("signups"), rs.getLong("took"), rs.getLong("paid")};
                });

        return new Results(o.getId(), o.getName(), OfferService.status(o, utc), o.getStartsAt(), to, redemptions, byPlan, money,
                now3[0], now3[1], now3[2], after[0], after[1], conv[0], conv[1], BusinessMetrics.percent(conv[1], conv[0]), conv[2],
                !o.isAutomatic());
    }
}
