package com.afklive.streamer.dto;

import com.afklive.streamer.model.ScheduledStream;
import com.afklive.streamer.model.StreamDestination;

import java.time.ZonedDateTime;
import java.util.List;

/** What the app shows for a scheduled stream (never the entity, which links to the full user). */
public record ScheduledStreamView(Long id, String videoTitle, String videoKey, List<String> destinations,
                                  ZonedDateTime scheduledTime, String status, String errorMessage) {

    public static ScheduledStreamView of(ScheduledStream s) {
        return new ScheduledStreamView(
                s.getId(),
                s.getMediaAsset() != null ? s.getMediaAsset().getTitle() : null,
                s.getVideoKey(),
                s.getDestinations() == null ? List.of() : s.getDestinations().stream().map(StreamDestination::getName).toList(),
                s.getScheduledTime(),
                s.getStatus() != null ? s.getStatus().name() : null,
                s.getErrorMessage());
    }
}
