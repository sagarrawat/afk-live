package com.afklive.streamer.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class StopStreamRequest {
    /** Local time of day to stop, "HH:mm". The next occurrence after now is used. */
    @NotBlank(message = "Time is required")
    @jakarta.validation.constraints.Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "Time must be HH:mm")
    private String time;

    /** The viewer's IANA time zone, e.g. "Asia/Kolkata". Defaults to UTC. */
    private String timeZone;
}
