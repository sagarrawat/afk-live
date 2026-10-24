package com.afklive.streamer.service;

import com.afklive.streamer.model.WorkerStatus;
import com.afklive.streamer.repository.WorkerStatusRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * GL-7: the plan's top quality isn't the server's capacity. Before a stream goes live this estimates
 * how much CPU its output needs and compares it with what the stream workers report as free, so a
 * creator hears "this won't keep up right now, 720p 30 fps will" instead of finding out from viewers
 * that the stream plays slowly (production, 24 Oct 2026: 1080p60 on a busy 4-CPU worker ran at 0.8x).
 * <p>
 * It is an estimate, labelled as one: a small table calibrated on our workers, not a measurement of
 * this stream. It warns; it only blocks when even an empty server couldn't run the stream.
 */
@Service
public class CapacityEstimator {

    /** A worker that hasn't reported in this long doesn't count. */
    static final Duration FRESH = Duration.ofSeconds(60);
    /**
     * Share of a machine streams can use. On 24 Oct 2026 the worker fell behind at 92 % CPU; the rest is
     * the operating system, relays, the JVM and spikes (a crossfade, a scene change).
     */
    static final double USABLE = 0.85;
    /** A stream that plays by stream copy (FO-2): reading and relaying files only. */
    static final double COPY_CPUS = 0.01;
    /** Decoding the sources; small since the studio decodes playout copies at the stream's size (FO-1). */
    static final double DECODE_CPUS = 0.1;

    private final WorkerStatusRepository workers;
    private final boolean copyMode;

    public CapacityEstimator(WorkerStatusRepository workers, @Value("${app.streaming.copy-mode:false}") boolean copyMode) {
        this.workers = workers;
        this.copyMode = copyMode;
    }

    /** A quality a creator can pick. */
    public record Quality(int height, int fps) {
        public String label() {
            return height + "p " + fps + " fps";
        }
    }

    /**
     * What the estimate says. {@code freeCpus} is null when no worker reported its load (then nothing is
     * known and nothing is said). {@code suggestion} is the best allowed quality that fits, or the
     * lightest one when none does; null when there's nothing lighter to offer.
     */
    public record Estimate(double needCpus, Double freeCpus, boolean fits, boolean impossible, Quality suggestion,
                           boolean suggestionFits, String message) {
    }

    /** CPUs to encode one stream: 1080p30 ≈ 1, 720p30 ≈ 0.5, scaled by frame rate (measured on our workers). */
    static double encodeCpus(int height, int fps) {
        double per30 = height <= 480 ? 0.3 : height <= 720 ? 0.5 : height <= 1080 ? 1.0 : height <= 1440 ? 1.8 : 4.0;
        return per30 * Math.max(1, fps) / 30.0;
    }

    /** CPUs one stream needs: encode plus decode in the studio, almost nothing by stream copy. */
    public static double streamCpus(int height, int fps, boolean byCopy) {
        return byCopy ? COPY_CPUS : round(encodeCpus(height, fps) + DECODE_CPUS);
    }

    /** {@code best}: free CPUs on the worker with the most room and a free slot; {@code biggest}: the most any worker could give one stream. */
    record Room(double best, double biggest) {
    }

    /** Null when no fresh worker reported its CPU load. Without its own CPU count, a worker is assumed to have one per stream slot. */
    static Room room(List<WorkerStatus> reports) {
        Double best = null;
        double biggest = 0;
        for (WorkerStatus w : reports) {
            if (w.getCpuPercent() == null) {
                continue;
            }
            int cpus = w.getCpus() != null && w.getCpus() > 0 ? w.getCpus() : Math.max(1, w.getCapacity());
            double usable = cpus * USABLE;
            biggest = Math.max(biggest, usable);
            double free = w.getCapacity() > 0 && w.getStreams() >= w.getCapacity() ? 0
                    : Math.max(0, usable - cpus * w.getCpuPercent() / 100.0);
            best = best == null ? free : Math.max(best, free);
        }
        return best == null ? null : new Room(round(best), round(biggest));
    }

    /** The estimate for {@code chosen} right now; {@code allowed}: qualities the creator's plan includes. */
    public Estimate estimate(Quality chosen, boolean playsByCopy, List<Quality> allowed) {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));
        return estimate(chosen, playsByCopy && copyMode, allowed, room(workers.findAllByHeartbeatAtAfterOrderByWorkerIdAsc(now.minus(FRESH))));
    }

    static Estimate estimate(Quality chosen, boolean byCopy, List<Quality> allowed, Room room) {
        double need = streamCpus(chosen.height(), chosen.fps(), byCopy);
        if (room == null) {
            return new Estimate(need, null, true, false, null, false, null);
        }
        boolean impossible = need > room.biggest();
        boolean fits = !impossible && need <= room.best();
        if (fits) {
            return new Estimate(need, room.best(), true, false, null, false, null);
        }
        // Lighter qualities, heaviest first (the sharpest picture that fits); at equal cost the taller one.
        // Never a higher frame rate than chosen: lighter means less of both.
        List<Quality> lighter = allowed.stream()
                .filter(q -> q.fps() <= chosen.fps() && q.height() <= chosen.height())
                .filter(q -> streamCpus(q.height(), q.fps(), false) < need)
                .sorted(Comparator.comparingDouble((Quality q) -> streamCpus(q.height(), q.fps(), false)).reversed()
                        .thenComparing(Quality::height, Comparator.reverseOrder()))
                .toList();
        Quality suggestion = lighter.stream().filter(q -> streamCpus(q.height(), q.fps(), false) <= room.best()).findFirst()
                .orElse(lighter.isEmpty() ? null : lighter.getLast());
        boolean suggestionFits = suggestion != null && streamCpus(suggestion.height(), suggestion.fps(), false) <= room.best();
        String message;
        if (impossible) {
            message = chosen.label() + " needs more than one of our servers can give a single stream, so it would play "
                    + "slower than real time for your viewers. Pick a lower quality"
                    + (suggestion == null ? "." : ", such as " + suggestion.label() + ".");
        } else {
            message = "Our servers are busy right now. By our estimate " + chosen.label() + " would run slower than real "
                    + "time, so viewers would see it play slowly."
                    + (suggestion == null ? "" : suggestionFits ? " " + suggestion.label() + " fits."
                    : " Even " + suggestion.label() + " may struggle until a server frees up.");
        }
        return new Estimate(need, room.best(), false, impossible, suggestion, suggestionFits, message);
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
