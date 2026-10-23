package com.afklive.streamer.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NT-3: which alerts reach the creator by email and by push, per kind. In the app, alerts always
 * show. Kinds group the alert types the way a creator thinks about them.
 */
public final class AlertPreferences {

    public record Kind(String id, String label, String description, List<String> types) {
    }

    public static final List<Kind> KINDS = List.of(
            new Kind("STREAM", "A stream goes down or comes back", "Down for a minute or more, failed, or stuck starting; and the all-clear.",
                    List.of(NotificationService.Types.STREAM_DOWN, NotificationService.Types.STREAM_RECOVERED,
                            NotificationService.Types.STREAM_FAILED, NotificationService.Types.START_STUCK)),
            new Kind("DESTINATION", "A platform stops receiving", "YouTube or another destination keeps refusing the stream, and when it's back.",
                    List.of(NotificationService.Types.DESTINATION_DOWN, NotificationService.Types.DESTINATION_RECOVERED)),
            new Kind("PHONE", "Your phone's signal", "Your phone stream drops for two minutes, and when it's back.",
                    List.of(NotificationService.Types.PHONE_LOST, NotificationService.Types.PHONE_BACK)),
            new Kind("SCHEDULE", "Schedules", "A scheduled start that didn't happen.", List.of(NotificationService.Types.SCHEDULE_FAILED)),
            new Kind("FILES", "Files that can't play", "A video or track was skipped because it couldn't be read.",
                    List.of(NotificationService.Types.ITEM_FAILED)),
            new Kind("ACCOUNT", "Storage, plan and payments", "Storage nearly full, a plan limit reached, a payment that failed.",
                    List.of(NotificationService.Types.STORAGE_ALMOST_FULL, NotificationService.Types.PLAN_LIMIT,
                            NotificationService.Types.PAYMENT_FAILED, NotificationService.Types.CREDIT_LIMIT)));

    public record Channels(boolean email, boolean push) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private AlertPreferences() {
    }

    /** Every kind with its channels; missing or unreadable entries are on. */
    public static Map<String, Channels> read(String json) {
        Map<String, Channels> stored = Map.of();
        if (json != null && !json.isBlank()) {
            try {
                stored = JSON.readValue(json, new TypeReference<Map<String, Channels>>() { });
            } catch (Exception ignored) {
                // fall back to all on
            }
        }
        Map<String, Channels> out = new LinkedHashMap<>();
        for (Kind k : KINDS) {
            out.put(k.id(), stored.getOrDefault(k.id(), new Channels(true, true)));
        }
        return out;
    }

    /** Keeps known kinds only. */
    public static String write(Map<String, Channels> prefs) {
        Map<String, Channels> clean = new LinkedHashMap<>();
        for (Kind k : KINDS) {
            Channels c = prefs == null ? null : prefs.get(k.id());
            clean.put(k.id(), c == null ? new Channels(true, true) : c);
        }
        try {
            return JSON.writeValueAsString(clean);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The channels for one alert type; types outside the kinds (tests, info) follow the defaults. */
    public static Channels forType(String json, String type) {
        Map<String, Channels> prefs = read(json);
        for (Kind k : KINDS) {
            if (k.types().contains(type)) {
                return prefs.get(k.id());
            }
        }
        return new Channels(true, true);
    }
}
