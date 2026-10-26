package com.afklive.streamer.streaming;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * FO-8: worker pools. Every worker belongs to one pool ({@code app.streaming.worker.pool}, default
 * {@code main}), reported in {@code stream_workers.pool}. Each run has a placement
 * ({@code stream_jobs.placement}), and workers decide at claim time, in the same conditional update that
 * claims a run, whether they may take it:
 * <ul>
 *   <li><b>Hard rule:</b> a run that reads the phone ingest (MediaMTX, on the main server) is claimed only by
 *       the pools listed in {@code app.streaming.pools.ingest} (default {@code main}). A run whose placement
 *       isn't known yet (created by an older web version) is treated the same way.</li>
 *   <li><b>Preference:</b> {@code app.streaming.pools.preference} maps a placement to the pool that should
 *       run it, e.g. {@code PLAYOUT=playout,PLAYLIST=playout}. A worker in another pool leaves such a run
 *       alone while a worker of the preferred pool reports a free slot and spare CPU, for at most
 *       {@link #PREFERENCE_GRACE}; when the preferred pool is full, draining or absent it claims at once.</li>
 * </ul>
 * With the defaults (one pool, {@code main}; no preference) every worker may claim every run: exactly the
 * behaviour before pools existed.
 */
public record WorkerPools(String pool, Set<String> ingestPools, Map<String, String> preference) {

    public static final String MAIN = "main";

    /** A playlist that needs nothing drawn or mixed live: can play by copy (FO-2). */
    public static final String PLAYOUT = "PLAYOUT";
    /** A playlist that needs the studio (overlays, music bed, scenes...). */
    public static final String PLAYLIST = "PLAYLIST";
    /** Reads the phone ingest server: only pools that can reach it. */
    public static final String PHONE = "PHONE";
    /** A classic single-file run. */
    public static final String OTHER = "OTHER";

    /** A run preferred elsewhere is claimed anyway after waiting this long (the preferred pool isn't taking it). */
    public static final Duration PREFERENCE_GRACE = Duration.ofSeconds(30);
    /** A worker whose status report is older than this doesn't count as available (it reports every 10 s). */
    public static final Duration AVAILABLE_REPORT_AGE = Duration.ofSeconds(30);

    public WorkerPools {
        pool = normalizePool(pool);
        ingestPools = Set.copyOf(ingestPools);
        preference = Map.copyOf(preference);
    }

    /** One pool, {@code main}, no preference: today's behaviour. */
    public static WorkerPools defaults() {
        return new WorkerPools(MAIN, Set.of(MAIN), Map.of());
    }

    /**
     * From configuration. {@code ingestPools} is a comma list (empty: every pool may read the ingest);
     * {@code preference} is {@code PLACEMENT=pool} pairs, comma separated. Unknown placements are ignored.
     */
    public static WorkerPools of(String pool, String ingestPools, String preference) {
        Set<String> ingest = new LinkedHashSet<>();
        if (ingestPools != null) {
            for (String p : ingestPools.split(",")) {
                if (!p.isBlank()) {
                    ingest.add(normalizePool(p));
                }
            }
        }
        Map<String, String> prefs = new LinkedHashMap<>();
        if (preference != null) {
            for (String pair : preference.split(",")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String placement = pair.substring(0, eq).trim().toUpperCase(Locale.ROOT);
                String target = pair.substring(eq + 1).trim();
                if (target.isEmpty() || !(PLAYOUT.equals(placement) || PLAYLIST.equals(placement) || OTHER.equals(placement))) {
                    continue; // PHONE is decided by the ingest list, never by preference
                }
                prefs.put(placement, normalizePool(target));
            }
        }
        return new WorkerPools(pool, ingest, prefs);
    }

    static String normalizePool(String p) {
        if (p == null || p.isBlank()) {
            return MAIN;
        }
        String s = p.trim().toLowerCase(Locale.ROOT);
        if (!s.matches("[a-z0-9][a-z0-9_-]{0,39}")) {
            throw new IllegalArgumentException("A worker pool name is 1-40 letters, digits, '-' or '_': " + p);
        }
        return s;
    }

    /** Whether this worker may run phone streams (and runs whose placement isn't known). */
    public boolean mayReadIngest() {
        return ingestPools.isEmpty() || ingestPools.contains(pool);
    }

    /** The pool runs of this placement prefer, or "" for none (also when that's this worker's own pool). */
    public String preferredFor(String placement) {
        String p = preference.get(placement);
        return p == null || p.equals(pool) ? "" : p;
    }

    /**
     * What a claim query needs to know about this worker at {@code now}: whether it may read the ingest, the pool
     * each placement prefers (if not this one), how long a preferred run waits for its pool, and what counts as
     * an available worker of that pool (reported recently, not draining, a free slot, CPU under
     * {@code maxCpuToClaim}).
     */
    public Claim claim(java.time.ZonedDateTime now, double maxCpuToClaim) {
        return new Claim(mayReadIngest(), preferredFor(PLAYOUT), preferredFor(PLAYLIST), preferredFor(OTHER),
                now.minus(PREFERENCE_GRACE), now.minus(AVAILABLE_REPORT_AGE), Math.max(0, maxCpuToClaim) * 100.0);
    }

    /** Parameters of the claim-time filter and preference (see {@code StreamJobRepository}). */
    public record Claim(boolean mayIngest, String preferPlayout, String preferPlaylist, String preferOther,
                        java.time.ZonedDateTime waitedBefore, java.time.ZonedDateTime availableAfter, double maxCpuPercent) {
    }

    /**
     * Where a run belongs, from its spec: a pure function, used by the web app when it creates the run and by
     * the owning worker to keep it current as live edits change the spec.
     */
    public static String placementOf(StreamSpec spec) {
        if (spec == null) {
            return null;
        }
        StudioSpec s = spec.studio();
        if (s == null) {
            return OTHER;
        }
        if (s.phone() != null || "PHONE".equals(s.source())) {
            return PHONE;
        }
        if ("PLAYLIST".equals(s.source())) {
            return CopyRuns.ineligible(s, null) == null ? PLAYOUT : PLAYLIST;
        }
        return null; // a source we don't know: treated like the phone (main pool only)
    }

    /** {@link #placementOf(StreamSpec)} from the stored JSON; null when unreadable. */
    public static String placementOf(String specJson) {
        if (specJson == null) {
            return null;
        }
        try {
            return placementOf(StreamSpec.fromJson(specJson));
        } catch (Exception e) {
            return null;
        }
    }
}
