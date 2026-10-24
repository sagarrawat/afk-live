package com.afklive.streamer.streaming;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;

/**
 * OPS-3: planned handover of a live stream from a draining worker (the old version) to another one
 * (the new version), with no gap on YouTube. All coordination is conditional updates on
 * {@code stream_jobs}; this class holds the timings and the pure decisions, so they are unit tested.
 *
 * <ol>
 *   <li>The draining worker <b>offers</b> one stream at a time ({@code handover_from}), with its
 *       playlist position and when it was taken.</li>
 *   <li>Another worker <b>claims</b> it ({@code handover_to}) and starts its own copy from that position
 *       (moved on by the time since), publishing YouTube destinations to the other ingest
 *       ({@link YouTubeIngest}); destinations without a backup ingest wait. While it runs it keeps a
 *       fenced beat ({@code handover_beat_at}).</li>
 *   <li>Once the encoder and every YouTube destination have been live for {@link Settings#readyAfter()}
 *       it marks the handover <b>ready</b>.</li>
 *   <li>The owner stops its other destinations, then <b>hands over</b>: one conditional update makes the
 *       claimer the owner (its last beat becomes the heartbeat it will fence on) and only succeeds if the
 *       claimer's beat is fresh. Then the owner stops its copy; YouTube fails over to the backup ingest.
 *       The claimer sees it owns the stream (it checks every quarter second) and connects the other
 *       destinations (about 1-2 s for them).</li>
 *   <li>The new owner moves its YouTube destinations back to the primary ingest, make-before-break from
 *       the same encoder, so the steady state is always the primary ingest Google asks for.</li>
 * </ol>
 * Anything that goes wrong leaves the owner running: a claim that never gets ready is dropped after
 * {@link Settings#timeout()}, a claimer that stops beating is dropped by the owner, and if the owner
 * itself dies the claimer's copy (already on air) takes over through the ordinary takeover.
 *
 * @param timeout    a claim that isn't ready this long after it was made is abandoned
 * @param readyAfter how long the new copy must be on air everywhere before the switch
 * @param beatStale  a claim whose beat is older than this is dropped by the owner
 */
public final class Handover {

    private Handover() {
    }

    public record Settings(Duration timeout, Duration readyAfter, Duration beatStale) {
        public static Settings defaults() {
            return new Settings(Duration.ofSeconds(60), Duration.ofSeconds(5), Duration.ofSeconds(15));
        }
    }

    /** A playlist position and when it was taken (epoch ms), offered with the stream. */
    public record OfferedPosition(String itemId, double offsetSeconds, int loopsCompleted, long atMillis) {
    }

    /**
     * Where the new copy should start: the offered position moved on by the time since it was taken
     * (both copies play in real time). If that runs past the item's end the playlist simply moves on to
     * the next item.
     */
    public static StudioSpec.Position advance(OfferedPosition offered, long nowMillis) {
        if (offered == null || offered.itemId() == null) {
            return null;
        }
        double elapsed = Math.max(0, nowMillis - offered.atMillis()) / 1000.0;
        // A position offered long ago (clock trouble, a stuck claim) is used as it was, not extrapolated.
        if (elapsed > 600) {
            elapsed = 0;
        }
        return new StudioSpec.Position(offered.itemId(), Math.max(0, offered.offsetSeconds()) + elapsed, offered.loopsCompleted());
    }

    /**
     * Whether the new copy is ready to take over: the encoder is live and every destination it publishes
     * to (those with a backup ingest) has been live for {@code readyAfter}. With none of those (only
     * destinations that must reconnect), the encoder alone must have been live that long.
     *
     * @param encoderLiveSinceNanos when the encoder last became live, or 0 if it isn't
     * @param liveSinceNanos        per destination position: when it became live (0 or absent: not live)
     */
    public static boolean ready(long encoderLiveSinceNanos, Collection<Integer> publishing, Map<Integer, Long> liveSinceNanos,
                                long nowNanos, Duration readyAfter) {
        long need = readyAfter.toNanos();
        if (encoderLiveSinceNanos == 0) {
            return false;
        }
        if (publishing.isEmpty()) {
            return nowNanos - encoderLiveSinceNanos >= need;
        }
        for (Integer position : publishing) {
            Long since = liveSinceNanos.get(position);
            if (since == null || since == 0 || nowNanos - since < need) {
                return false;
            }
        }
        return true;
    }
}
