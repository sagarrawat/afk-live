package com.afklive.streamer.service;

import com.afklive.streamer.model.PlanType;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * FO-4: the live video bitrate for each output height class (720, 1080, 1440, 2160, by the picture's short
 * side) and plan. Read once at start from {@code app.streaming.bitrate.<plan>.<class>} in kbps at 30 fps
 * (env {@code APP_STREAMING_BITRATE_FREE_720} and so on), with {@code default} for every plan without its
 * own value; 60 fps streams get 1.5x, as before. With nothing set every plan gets the built-in ladder, so
 * this changes nothing until someone sets a value.
 * <p>
 * A value below YouTube's ingest guidance for its class ({@link #FLOORS}: 720p30 1.5 Mbps, the bottom of
 * YouTube's 720p30 range; 1080p30 5 Mbps, the repo rule; 1440p30 6 Mbps; 2160p30 13 Mbps) is raised to the
 * floor with a warning; one that isn't a number is ignored with a warning. A typo never stops a worker.
 * <p>
 * The web app resolves the bitrate once when a run goes live and records it in the run's spec
 * ({@code StudioSpec.videoKbps}); workers encode at the recorded value, so a plan change or a new setting
 * never changes a running stream, and profile copies (copy mode) are made and looked up at that value.
 */
@Slf4j
public final class VideoBitrates {

    /** Height classes by short side, lowest first. */
    public static final List<Integer> CLASSES = List.of(720, 1080, 1440, 2160);
    /** What the encoder used before this was configurable (kbps at 30 fps): the default for every plan. */
    public static final Map<Integer, Integer> BUILT_IN = Map.of(720, 4000, 1080, 6000, 1440, 9000, 2160, 15000);
    /** The lowest allowed value per class (kbps at 30 fps); 60 fps gets 1.5x, which meets YouTube's 60 fps ranges too. */
    public static final Map<Integer, Integer> FLOORS = Map.of(720, 1500, 1080, 5000, 1440, 6000, 2160, 13000);
    /** Nothing above this: a value this high is a typo (an extra zero), not a choice. */
    public static final int MAX_KBPS = 50_000;
    public static final String DEFAULT_PLAN = "default";
    public static final String PREFIX = "app.streaming.bitrate.";

    public static final VideoBitrates BUILT_IN_LADDER = new VideoBitrates(Map.of(), Map.of());

    /** Per class for plans without their own value. */
    private final Map<Integer, Integer> defaults;
    private final Map<PlanType, Map<Integer, Integer>> perPlan;

    VideoBitrates(Map<Integer, Integer> defaults, Map<PlanType, Map<Integer, Integer>> perPlan) {
        this.defaults = Map.copyOf(defaults);
        Map<PlanType, Map<Integer, Integer>> p = new EnumMap<>(PlanType.class);
        perPlan.forEach((k, v) -> p.put(k, Map.copyOf(v)));
        this.perPlan = Collections.unmodifiableMap(p);
    }

    /**
     * From configuration: {@code lookup} returns the raw value of a property name (null or blank when unset),
     * e.g. Spring's {@code Environment::getProperty}, which also finds {@code APP_STREAMING_BITRATE_FREE_720}.
     */
    public static VideoBitrates fromConfig(Function<String, String> lookup) {
        Map<Integer, Integer> defaults = read(lookup, DEFAULT_PLAN);
        Map<PlanType, Map<Integer, Integer>> perPlan = new EnumMap<>(PlanType.class);
        for (PlanType plan : PlanType.values()) {
            Map<Integer, Integer> m = read(lookup, plan.name().toLowerCase(Locale.ROOT));
            if (!m.isEmpty()) {
                perPlan.put(plan, m);
            }
        }
        return new VideoBitrates(defaults, perPlan);
    }

    private static Map<Integer, Integer> read(Function<String, String> lookup, String plan) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        for (int cls : CLASSES) {
            String name = PREFIX + plan + "." + cls;
            String raw = lookup.apply(name);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            Integer v = parse(name, raw, cls);
            if (v != null) {
                out.put(cls, v);
            }
        }
        return out;
    }

    /** A configured value, clamped to the class's floor and {@link #MAX_KBPS}; null (ignored) when it isn't a number. */
    static Integer parse(String name, String raw, int cls) {
        String t = raw.trim().toLowerCase(Locale.ROOT);
        if (t.endsWith("k")) {
            t = t.substring(0, t.length() - 1).trim();
        }
        int v;
        try {
            v = Integer.parseInt(t);
        } catch (NumberFormatException e) {
            log.warn("{}={} isn't a whole number of kbps; ignored (using the default)", name, raw);
            return null;
        }
        int floor = FLOORS.get(cls);
        if (v < floor) {
            log.warn("{}={} kbps is below YouTube's ingest guidance for {}p at 30 fps; using {} kbps", name, v, cls, floor);
            return floor;
        }
        if (v > MAX_KBPS) {
            log.warn("{}={} kbps is above {} kbps; using {} kbps", name, v, MAX_KBPS, MAX_KBPS);
            return MAX_KBPS;
        }
        return v;
    }

    /** The height class of a picture with this short side (anything under 1080 is the 720 class). */
    public static int classOf(int shortSide) {
        int cls = CLASSES.getFirst();
        for (int c : CLASSES) {
            if (shortSide >= c) {
                cls = c;
            }
        }
        return cls;
    }

    /** kbps at 30 fps for this plan and class: the plan's value, else {@code default}, else the built-in ladder. */
    public int baseKbps(PlanType plan, int cls) {
        Map<Integer, Integer> own = plan == null ? null : perPlan.get(plan);
        if (own != null && own.containsKey(cls)) {
            return own.get(cls);
        }
        return defaults.getOrDefault(cls, BUILT_IN.get(cls));
    }

    /** The live video bitrate (kbps) a new run of this plan sends for a picture with this short side and frame rate. */
    public int videoKbps(PlanType plan, int shortSide, int fps) {
        return withFps(baseKbps(plan, classOf(shortSide)), fps);
    }

    /** What the encoder sent before this was configurable, and what a run without a recorded bitrate sends. */
    public static int builtInKbps(int shortSide, int fps) {
        return withFps(BUILT_IN.get(classOf(shortSide)), fps);
    }

    private static int withFps(int kbps, int fps) {
        return fps > 30 ? kbps * 3 / 2 : kbps;
    }

    /** A run's recorded bitrate, or the built-in ladder's when none is recorded (a run from before FO-4). */
    public static int recordedOrBuiltIn(Integer recorded, int shortSide, int fps) {
        return recorded != null && recorded > 0 ? recorded : builtInKbps(shortSide, fps);
    }

    /** One line per plan for the start-up log, e.g. {@code FREE 720=4000 1080=6000 ...}. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (PlanType plan : PlanType.values()) {
            if (!sb.isEmpty()) {
                sb.append("; ");
            }
            sb.append(plan.name());
            for (int cls : CLASSES) {
                sb.append(' ').append(cls).append('=').append(baseKbps(plan, cls));
            }
        }
        return sb.toString();
    }

    /** Whether any plan differs from the built-in ladder. */
    public boolean isBuiltIn() {
        for (PlanType plan : PlanType.values()) {
            for (int cls : CLASSES) {
                if (baseKbps(plan, cls) != BUILT_IN.get(cls)) {
                    return false;
                }
            }
        }
        return true;
    }
}
