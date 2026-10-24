package com.afklive.streamer.service;

import com.afklive.streamer.dto.StreamDtos.DestinationHealth;
import com.afklive.streamer.dto.StreamDtos.Health;
import com.afklive.streamer.dto.StreamDtos.Metric;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.model.StreamJobDestination;
import com.afklive.streamer.streaming.StreamSpec;
import com.afklive.streamer.util.Platforms;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RE-3: turns the worker's numbers into green / amber / red with a sentence a creator understands.
 * RE-8: and says, per destination and for the stream, whether the platforms are really receiving it.
 */
public final class StreamHealth {

    public static final String GREEN = "green";
    public static final String AMBER = "amber";
    public static final String RED = "red";

    /** RE-8, per destination (the worker's words, see {@code StreamRunner.ON}). */
    public static final String ON = "ON";
    public static final String CONNECTING = "CONNECTING";
    public static final String NOT_REACHING = "NOT_REACHING";

    /** RE-8, the whole stream. */
    public static final String REACH_ALL = "ALL";
    public static final String REACH_SOME = "SOME";
    public static final String REACH_NONE = "NONE";
    public static final String REACH_CONNECTING = "CONNECTING";

    private StreamHealth() {
    }

    /**
     * RE-8: whether this destination's platform is receiving; null when it is turned off or finished. A
     * worker of the previous release doesn't report it: then LIVE counts as on, and a reconnect with an
     * error as not reaching.
     */
    public static String reachOf(StreamJobDestination d) {
        String status = d.getStatus() == null ? "CONNECTING" : d.getStatus();
        if ("OFF".equals(status) || "STOPPED".equals(status)) {
            return null;
        }
        if (d.getReach() != null) {
            return d.getReach();
        }
        if ("LIVE".equals(status)) {
            return ON;
        }
        return d.getLastError() != null && !d.getLastError().isBlank() ? NOT_REACHING : CONNECTING;
    }

    /**
     * RE-8, the stream badge: ALL ("Live": at least one platform receiving and none given up on), SOME
     * ("Live with problems": some receiving, some not reaching), NONE ("Not reaching anyone": every
     * destination not reaching) or CONNECTING (nothing confirmed yet); null with no destination to reach.
     */
    public static String reach(List<StreamJobDestination> destinations) {
        int on = 0;
        int notReaching = 0;
        int counted = 0;
        for (StreamJobDestination d : destinations) {
            String r = reachOf(d);
            if (r == null) {
                continue;
            }
            counted++;
            if (ON.equals(r)) {
                on++;
            } else if (NOT_REACHING.equals(r)) {
                notReaching++;
            }
        }
        if (counted == 0) {
            return null;
        }
        if (on > 0) {
            return notReaching > 0 ? REACH_SOME : REACH_ALL;
        }
        return notReaching == counted ? REACH_NONE : REACH_CONNECTING;
    }

    /** Destination position to its address, from the run's spec (used on the server only, never sent to the browser). */
    public static Map<Integer, String> urls(StreamJob j) {
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

    /** "YouTube" for a YouTube destination; the creator's own name for a server we don't know. */
    public static String label(StreamJobDestination d, String url) {
        String p = Platforms.label(url);
        return p != null ? p : d.getName() == null || d.getName().isBlank() ? "the destination" : d.getName();
    }

    /** Why a destination isn't reaching its platform, in plain words. */
    public static String why(StreamJobDestination d, String url) {
        String raw = d.getReach() != null ? d.getReachReason() : d.getLastError();
        if (raw == null || raw.isBlank()) {
            return label(d, url) + " isn't receiving the stream yet.";
        }
        return DestinationTestService.explain(raw, url);
    }

    /** "On YouTube", "Connecting to YouTube", "Not reaching YouTube. YouTube refused the stream key. ..." */
    static String message(StreamJobDestination d, String reach, String url) {
        String label = label(d, url);
        return switch (reach) {
            case ON -> "On " + label;
            case CONNECTING -> "Connecting to " + label;
            default -> "Not reaching " + label + ". " + why(d, url);
        };
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
                // Slower than real time means YouTube receives it slower than it plays (24 Oct 2026): say so.
                case AMBER -> "Encoding slightly slower than real time; if it stays there, viewers see it play slowly.";
                default -> "The server can't keep up, so viewers see the stream play slowly. Lower the quality to 720p 30 fps.";
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
        Map<Integer, String> urls = urls(j);
        for (StreamJobDestination d : j.getDestinations()) {
            String status = d.getStatus() == null ? "CONNECTING" : d.getStatus();
            if ("OFF".equals(status)) {
                continue; // turned off by the creator while live (DS-5)
            }
            String reach = reachOf(d);
            String level = reach == null ? AMBER : switch (reach) {
                case ON -> GREEN;
                case CONNECTING -> AMBER;
                default -> RED;
            };
            String url = urls.get(d.getPosition());
            dests.add(new DestinationHealth(d.getName(), status, level, d.getLastError(), d.getRestarts(),
                    Platforms.label(url), reach, reach == null ? null : message(d, reach, url)));
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
