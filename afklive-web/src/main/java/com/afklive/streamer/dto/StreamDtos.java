package com.afklive.streamer.dto;

import com.afklive.streamer.streaming.StudioSpec;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/** Request and response shapes for /api/streams. */
public final class StreamDtos {

    private StreamDtos() {
    }

    public record CreateStream(String name, String source, String orientation, Integer qualityHeight, Integer fps) {
    }

    /** Every field optional: only those present change. */
    public record UpdateStream(String name, String orientation, Integer qualityHeight, Integer fps,
                               String playbackOrder, Integer loopCount, String musicOrder, String audioMode,
                               Double videoVolume, Double musicVolume, String timeZone,
                               Boolean endOnInputLoss, Integer inputGraceSeconds, Integer srtLatencyMs) {
        /** Settings changes without the phone fields. */
        public UpdateStream(String name, String orientation, Integer qualityHeight, Integer fps, String playbackOrder,
                            Integer loopCount, String musicOrder, String audioMode, Double videoVolume, Double musicVolume,
                            String timeZone) {
            this(name, orientation, qualityHeight, fps, playbackOrder, loopCount, musicOrder, audioMode, videoVolume,
                    musicVolume, timeZone, null, null, null);
        }
    }

    public record MediaRef(Long id, String title, Double durationSeconds, Integer width, Integer height,
                           String contentType, String status) {
    }

    public record TargetView(Long destinationId, String name, String type, boolean enabled) {
    }

    public record TargetRequest(Long destinationId, boolean enabled) {
    }

    public record Metric(String name, String value, String level, String explanation) {
    }

    public record DestinationHealth(String name, String status, String level, String error, int restarts) {
    }

    /** RE-3: green / amber / red per metric, each with a plain-language explanation. */
    public record Health(String overall, List<Metric> metrics, List<DestinationHealth> destinations) {
    }

    /**
     * GL-2: DRAFT, STARTING, LIVE, STOPPING, ENDED or ERROR.
     */
    public record LiveState(String state, Long jobId, ZonedDateTime startedAt, long uptimeSeconds,
                            ZonedDateTime stopAt, String scene, Boolean inputLive, Map<String, Object> nowPlaying,
                            Health health, String endReason) {
    }

    public record ScheduleView(Long id, String kind, ZonedDateTime startAt, ZonedDateTime endAt, List<String> daysOfWeek,
                               String startTime, String endTime, String timeZone, List<String> skipDates,
                               boolean enabled, ZonedDateTime nextStart, String lastError) {
    }

    /** startAt/endAt are local date-times (yyyy-MM-ddTHH:mm) in {@code timeZone}; startTime/endTime HH:mm. */
    public record ScheduleRequest(String kind, String startAt, String endAt, List<String> daysOfWeek,
                                  String startTime, String endTime, String timeZone, List<String> skipDates,
                                  Boolean enabled) {
    }

    public record StreamView(Long id, String name, String source, String orientation, int qualityHeight, int fps,
                             String playbackOrder, int loopCount, String musicOrder, String audioMode,
                             double videoVolume, double musicVolume, String timeZone,
                             List<StudioSpec.OverlaySetting> overlays, List<StudioSpec.SceneSetting> scenes,
                             List<MediaRef> playlist, List<MediaRef> music, double totalRuntimeSeconds,
                             List<TargetView> destinations, List<ScheduleView> schedules, LiveState live,
                             PhoneSettings phone) {
    }

    /** Phone streams: what happens when the signal drops, and the SRT buffer. */
    public record PhoneSettings(boolean endOnInputLoss, int inputGraceSeconds, int srtLatencyMs,
                                ZonedDateTime lastConnectedAt) {
    }

    public record StreamSummary(Long id, String name, String source, String orientation, int qualityHeight,
                                int playlistCount, LiveState live) {
    }

    /** GL-1: one pre-flight check; {@code fix} says where to go to fix it. */
    public record Check(String id, boolean ok, String message, String fix) {
    }

    public record Preflight(boolean ok, List<Check> checks) {
    }

    /** GL-3: NOW, AT (local date-time in timeZone) or AFTER_PLAYLIST. */
    public record StopRequest(String mode, String at, String timeZone) {
    }

    public record QualityOption(int height, int fps, boolean allowed, String reason) {
    }

    public record RunView(Long jobId, ZonedDateTime startedAt, ZonedDateTime endedAt, String status, String endReason,
                          int restarts) {
    }
}
