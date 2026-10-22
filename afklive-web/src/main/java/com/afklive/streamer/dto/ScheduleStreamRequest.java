package com.afklive.streamer.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.ZonedDateTime;
import java.util.List;

/** A request to start a stream later. Video and destinations must belong to the user. */
@Data
public class ScheduleStreamRequest {

    /** Storage key of a video in the user's library. */
    @NotBlank(message = "Choose a video")
    private String videoKey;

    /** Ids of the user's saved destinations to stream to. */
    @NotEmpty(message = "Choose at least one destination")
    private List<Long> destinationIds;

    @NotNull(message = "Scheduled time is required")
    @Future(message = "Scheduled time must be in the future")
    private ZonedDateTime scheduledTime;

    private String musicName;
    private String musicVolume = "1.0";
    private int loopCount = -1;
    private String streamMode = "original";
    private boolean muteVideoAudio = true;
}
