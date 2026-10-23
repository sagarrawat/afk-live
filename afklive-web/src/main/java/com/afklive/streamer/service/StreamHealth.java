package com.afklive.streamer.service;

import com.afklive.streamer.dto.StreamDtos.DestinationHealth;
import com.afklive.streamer.dto.StreamDtos.Health;
import com.afklive.streamer.dto.StreamDtos.Metric;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.model.StreamJobDestination;

import java.time.Duration;
import com.afklive.streamer.streaming.StreamSpec;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RE-3: turns the worker's numbers into green / amber / red with a sentence a creator understands.
 */
public final class StreamHealth {

    public static final String GREEN = "green";
    public static final String AMBER = "amber";
    public static final String RED = "red";

    /** RE-8, per destination: the platform is receiving (ON), we're still connecting, or it isn't reaching it (DOWN). */
    public static final String ON = "ON";
    public static final String CONNECTING = "CONNECTING";
    public static final String DOWN = "DOWN";

    /** RE-8, the whole stream: every destination ON, some, none, or still connecting with no failure yet. */
    public static final String REACH_ALL = "ALL";
    public static final String REACH_SOME = "SOME";
    public static final String REACH_NONE = "NONE";
    public static final String REACH_CONNECTING = "CONNECTING";

    /** A destination that hasn't been confirmed for this long, with no reconnect in between, counts as down. */
    static final Duration CONNECT_GRACE = Duration.ofSeconds(30);

    /**
     * RE-8: a destination is ON only once the worker saw it take the stream steadily (status LIVE).
     * A failed attempt is DOWN at once; a first attempt gets {@link #CONNECT_GRACE} to get there.
     * Null for one that is turned off or finished.
     */
    public static String reachOf(StreamJobDestination d, ZonedDateTime now) {
        String status = d.getStatus() == null ? "CONNECTING" : d.getStatus();
        return switch (status) {
            case "LIVE" -> ON;
            case "OFF", "STOPPED" -> null;
            default -> {
                if (d.getLastError() != null && !d.getLastError().isBlank()) {
                    yield DOWN;
                }
                ZonedDateTime since = d.getUpdatedAt();
                yield since != null && since.isBefore(now.minus(CONNECT_GRACE)) ? DOWN : CONNECTING;
            }
        };
    }

    /** RE-8: the stream-level answer to "is anyone receiving it?"; null when it has no destination to reach. */
    public static String reach(List<StreamJobDestination> destinations, ZonedDateTime now) {
        int on = 0;
        int down = 0;
        int connecting = 0;
        for (StreamJobDestination d : destinations) {
            String r = reachOf(d, now);
            if (ON.equals(r)) on++;
            else if (DOWN.equals(r)) down++;
            else if (CONNECTING.equals(r)) connecting++;
        }
        if (on + down + connecting == 0) {
            return null;
        }
        if (on > 0) {
            return down > 0 ? REACH_SOME : REACH_ALL;
        }
        return connecting > 0 ? REACH_CONNECTING : REACH_NONE;
    }

    /** Destination position to its address, from the run's spec (never sent to the browser). */
    static Map<Integer, String> urls(StreamJob j) {
        Map<Integer, String> out = new HashMap<>();
        if (j.getSpec() == null) {
            return out;
        }
        try {
            List<StreamSpec.Destination> ds = StreamSpec.fromJson(j.getSpec()).destinations();
            for (int i = 0; i < ds.size(); i++) {
                out.put(i, ds.get(i).url());
            }
        } catch (Exception ignored) {
            // unreadable spec: no platform names
        }
        return out;
    }

    /** "On YouTube", "Not reaching YouTube. The platform refused the stream key. …", in plain words. */
    static String message(String reach, String label, String error, String url) {
        if (ON.equals(reach)) {
            return "On " + label;
        }
        if (CONNECTING.equals(reach)) {
            return "Connecting to " + label + "…";
        }
        return "Not reaching " + label + ". " + (error == null || error.isBlank()
                ? label + " hasn't started receiving the stream."
                : PlatformErrors.explain(error, url));
    }

    private StreamHealth() {
    }

    /** Expected encoder output (kbps) for the profile, matching the bitrate ladder. */
    static double expectedKbps(int height, int fps) {
        double base = height >= 2160 ? 15000 : height >= 1440 ? 9000 : height >= 1080 ? 6000 : 4000;
        return fps > 30 ? base * 1.5 : base;
    }

    public static Health of(StreamJob j, int height, int fps) {
        List<Metric> metrics = new ArrayList<>();
        boolean reporting = j.getHeartbeatAt() != null
                && Duration.between(j.getHeartbeatAt(), ZonedDateTime.now()).getSeconds() < 15;
        if (!reporting && j.getHeartbeatAt() != null) {
            metrics.add(new Metric("status", "delayed", AMBER, "We haven't heard from the stream server for a few seconds."));
        }

        Double f = j.getFps();
        if (f != null) {
            String level = f >= fps * 0.95 ? GREEN : f >= fps * 0.8 ? AMBER : RED;
            metrics.add(new Metric("fps", String.format("%.0f", f), level, switch (level) {
                case GREEN -> "Smooth: " + fps + " frames a second.";
                case AMBER -> "Slightly below " + fps + " fps; viewers may notice small stutters.";
                default -> "Well below " + fps + " fps; the picture will stutter.";
            }));
        }

        Double speed = j.getSpeed();
        if (speed != null) {
            String level = speed >= 0.98 ? GREEN : speed >= 0.9 ? AMBER : RED;
            metrics.add(new Metric("speed", String.format("%.2fx", speed), level, switch (level) {
                case GREEN -> "Encoding in real time.";
                case AMBER -> "Encoding slightly slower than real time; we'll keep it live by skipping frames.";
                default -> "The server can't keep up; try a lower quality.";
            }));
        }

        Double kbps = j.getBitrateKbps();
        if (kbps != null) {
            double expected = expectedKbps(height, fps);
            String level = kbps >= expected * 0.7 ? GREEN : kbps >= expected * 0.4 ? AMBER : RED;
            metrics.add(new Metric("bitrate", String.format("%.1f Mbps", kbps / 1000), level, switch (level) {
                case GREEN -> "Sending at full quality.";
                case AMBER -> "Sending below the usual quality.";
                default -> "Sending far below the usual quality; YouTube may show it blurry.";
            }));
        }

        Long drops = j.getDroppedFrames();
        if (drops != null) {
            String level = drops == 0 ? GREEN : drops < 300 ? AMBER : RED;
            metrics.add(new Metric("dropped", String.valueOf(drops), level,
                    drops == 0 ? "No frames dropped." : "Frames dropped since the start; a few are normal after a hiccup."));
        }

        if (Boolean.FALSE.equals(j.getInputLive())) {
            metrics.add(new Metric("input", "lost", AMBER, "Your phone's signal dropped: viewers see the BRB scene until it's back."));
        } else if (Boolean.TRUE.equals(j.getInputLive())) {
            metrics.add(new Metric("input", "live", GREEN, "Receiving your phone's stream."));
        }

        List<DestinationHealth> dests = new ArrayList<>();
        ZonedDateTime now = ZonedDateTime.now();
        Map<Integer, String> urls = urls(j);
        for (StreamJobDestination d : j.getDestinations()) {
            String status = d.getStatus() == null ? "CONNECTING" : d.getStatus();
            if ("OFF".equals(status)) {
                continue; // turned off by the creator while live (DS-5)
            }
            String reach = reachOf(d, now);
            String level = reach == null ? AMBER : switch (reach) {
                case ON -> GREEN;
                case CONNECTING -> AMBER;
                default -> RED;
            };
            String url = urls.get(d.getPosition());
            String platform = PlatformErrors.platform(url);
            String label = platform != null ? platform : d.getName() == null ? "the destination" : d.getName();
            dests.add(new DestinationHealth(d.getName(), status, level, d.getLastError(), d.getRestarts(), platform,
                    reach, reach == null ? null : message(reach, label, d.getLastError(), url)));
        }

        String overall = GREEN;
        for (Metric m : metrics) {
            overall = worse(overall, m.level());
        }
        for (DestinationHealth d : dests) {
            overall = worse(overall, d.level());
        }
        return new Health(overall, metrics, dests);
    }

    private static String worse(String a, String b) {
        if (RED.equals(a) || RED.equals(b)) {
            return RED;
        }
        if (AMBER.equals(a) || AMBER.equals(b)) {
            return AMBER;
        }
        return GREEN;
    }
}
