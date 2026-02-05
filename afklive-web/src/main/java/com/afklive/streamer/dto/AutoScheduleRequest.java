package com.afklive.streamer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;

@Data
public class AutoScheduleRequest {
    @NotEmpty(message = "Time slots are required")
    private List<String> timeSlots;

    @NotBlank(message = "Start date is required")
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "Date must be in YYYY-MM-DD format")
    private String startDate;

    private boolean useAi;
    private String topic;
}
