package com.afklive.streamer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Homepage measurement without an analytics tool: homepage view, "Start free" click, sign-up, first
 * go-live and first time a platform received the stream, counted per day (UTC).
 * <ul>
 *   <li>Views and clicks come from the site's beacon and are stored only as a counter per day and step:
 *       no visitor id, no cookie, no IP, no user agent. Requests that say they're a bot, a crawler, a
 *       preview fetcher or a script are dropped (rough on purpose: the beacon also needs JavaScript).</li>
 *   <li>Sign-ups are accounts made that day ({@code users.created_at}); go-lives and reached come from the
 *       existing stream runs and their timeline (RE-9).</li>
 * </ul>
 */
@Service
@Slf4j
public class SiteFunnelService {

    public static final String VIEW = "view";
    public static final String START = "start";
    static final Set<String> STEPS = Set.of(VIEW, START);
    /** Per instance, per day and step: a flood can't grow the table or skew the numbers without bound. */
    static final long DAILY_CAP = 50_000;
    static final int MAX_DAYS = 90;

    private static final Pattern BOT = Pattern.compile(
            "bot|crawl|spider|slurp|scrap|headless|lighthouse|pagespeed|preview|facebookexternalhit|whatsapp|telegram|"
                    + "curl|wget|python|java/|okhttp|go-http|httpclient|axios|node-fetch|monitor|uptime|pingdom|scan|phantom|selenium|playwright|puppeteer",
            Pattern.CASE_INSENSITIVE);

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final Map<String, AtomicLong> todayCounts = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public SiteFunnelService(NamedParameterJdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    SiteFunnelService(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Whether a request's user agent looks like a person's browser. */
    public static boolean looksHuman(String userAgent) {
        if (userAgent == null || userAgent.isBlank() || userAgent.length() > 512) {
            return false;
        }
        return userAgent.startsWith("Mozilla/") && !BOT.matcher(userAgent).find();
    }

    /** Counts one step if it's a known step from a browser. Returns whether it was counted. */
    public boolean record(String step, String userAgent) {
        String s = step == null ? "" : step.trim().toLowerCase(Locale.ROOT);
        if (!STEPS.contains(s) || !looksHuman(userAgent)) {
            return false;
        }
        LocalDate day = LocalDate.now(clock);
        String key = day + "|" + s;
        if (!todayCounts.containsKey(key)) {
            todayCounts.keySet().removeIf(old -> !old.startsWith(day.toString())); // yesterday's caps
        }
        AtomicLong n = todayCounts.computeIfAbsent(key, k -> new AtomicLong());
        if (n.incrementAndGet() > DAILY_CAP) {
            return false;
        }
        try {
            jdbc.update("INSERT INTO site_funnel_days(day, step, count) VALUES (:day, :step, 1) "
                    + "ON CONFLICT (day, step) DO UPDATE SET count = site_funnel_days.count + 1",
                    new MapSqlParameterSource().addValue("day", day).addValue("step", s));
            return true;
        } catch (RuntimeException e) {
            log.debug("Funnel count not stored: {}", e.getMessage());
            return false;
        }
    }

    /**
     * One day of the funnel. {@code firstGoLives}: accounts that pressed Go live for the first time that day;
     * {@code firstReached}: accounts whose stream a platform received for the first time that day.
     */
    public record Day(LocalDate day, long views, long startClicks, long signUps, long firstGoLives, long firstReached) {
    }

    /** The last {@code days} days (UTC), newest first. */
    public List<Day> daily(int days) {
        int n = Math.max(1, Math.min(MAX_DAYS, days));
        LocalDate today = LocalDate.now(clock);
        LocalDate from = today.minusDays(n - 1L);
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("from", from)
                .addValue("fromTs", Timestamp.from(from.atStartOfDay(ZoneOffset.UTC).toInstant()));
        Map<LocalDate, long[]> rows = new HashMap<>();
        jdbc.query("SELECT day, step, count FROM site_funnel_days WHERE day >= :from", p, rs -> {
            long[] r = rows.computeIfAbsent(rs.getObject("day", LocalDate.class), k -> new long[5]);
            r[VIEW.equals(rs.getString("step")) ? 0 : 1] += rs.getLong("count");
        });
        perDay(rows, 2, "SELECT (created_at AT TIME ZONE 'UTC')::date AS d, count(*) AS n FROM users "
                + "WHERE created_at >= :fromTs GROUP BY 1", p);
        perDay(rows, 3, "SELECT (first AT TIME ZONE 'UTC')::date AS d, count(*) AS n FROM "
                + "(SELECT user_id, min(start_time) AS first FROM stream_jobs GROUP BY user_id) f "
                + "WHERE first >= :fromTs GROUP BY 1", p);
        perDay(rows, 4, "SELECT (first AT TIME ZONE 'UTC')::date AS d, count(*) AS n FROM "
                + "(SELECT j.user_id, min(e.at) AS first FROM stream_events e JOIN stream_jobs j ON j.id = e.job_id "
                + "WHERE e.kind = 'REACHED' GROUP BY j.user_id) f WHERE first >= :fromTs GROUP BY 1", p);
        List<Day> out = new ArrayList<>();
        for (LocalDate d = today; !d.isBefore(from); d = d.minusDays(1)) {
            long[] r = rows.getOrDefault(d, new long[5]);
            out.add(new Day(d, r[0], r[1], r[2], r[3], r[4]));
        }
        return out;
    }

    private void perDay(Map<LocalDate, long[]> rows, int column, String sql, MapSqlParameterSource p) {
        jdbc.query(sql, p, rs -> {
            rows.computeIfAbsent(rs.getObject("d", LocalDate.class), k -> new long[5])[column] += rs.getLong("n");
        });
    }
}
