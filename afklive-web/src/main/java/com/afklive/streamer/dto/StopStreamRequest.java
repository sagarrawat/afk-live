package com.afklive.streamer.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class StopStreamRequest {
    @NotBlank(message = "Time is required")
    private String time;
}
