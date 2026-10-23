package com.afklive.streamer.streaming;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Everything a worker needs to run a stream, decided and validated by the web app. Stored as JSON
 * on the job, so the web app and workers share nothing but the database.
 *
 * @param username        owner (email)
 * @param videoKey        storage key of the video (already checked to be the owner's)
 * @param musicKey        storage key of the music file, "stock:&lt;id&gt;" for stock music, or null
 * @param musicVolume     0..2
 * @param loopCount       -1 loops forever
 * @param watermarkKey    storage key of the uploaded watermark image, or null
 * @param maxHeight       output height cap from the plan and the requested quality
 * @param scheduledStreamId the schedule that started this stream, if any
 * @param destinations    where to send it; urls/keys already validated
 * @param streamId        the saved stream this run belongs to (null for one-off runs)
 * @param studio          set for studio streams (playlists, phone input, scenes, overlays); null for a
 *                        single file run by the classic encoder
 */
public record StreamSpec(
        String username,
        String videoKey,
        String musicKey,
        String musicVolume,
        int loopCount,
        String watermarkKey,
        boolean muteVideoAudio,
        String streamMode,
        int maxHeight,
        boolean overlayEnabled,
        String overlayTemplate,
        Long scheduledStreamId,
        List<Destination> destinations,
        Long streamId,
        StudioSpec studio
) {

    /** A classic single-file run. */
    public StreamSpec(String username, String videoKey, String musicKey, String musicVolume, int loopCount,
                      String watermarkKey, boolean muteVideoAudio, String streamMode, int maxHeight,
                      boolean overlayEnabled, String overlayTemplate, Long scheduledStreamId, List<Destination> destinations) {
        this(username, videoKey, musicKey, musicVolume, loopCount, watermarkKey, muteVideoAudio, streamMode, maxHeight,
                overlayEnabled, overlayTemplate, scheduledStreamId, destinations, null, null);
    }

    /** A studio run of a saved stream. */
    public static StreamSpec studio(String username, Long streamId, StudioSpec studio, List<Destination> destinations) {
        return new StreamSpec(username, null, null, null, 0, null, false, "studio", studio.height(), false, null, null,
                destinations, streamId, studio);
    }

    /** A platform to send to. {@code url} is a full rtmp/rtmps/srt URL or a bare YouTube stream key. */
    public record Destination(String name, String url) {
    }

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public String toJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize stream spec", e);
        }
    }

    public static StreamSpec fromJson(String json) {
        try {
            return JSON.readValue(json, StreamSpec.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read stream spec", e);
        }
    }
}
