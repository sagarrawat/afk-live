package com.afklive.streamer.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ImportRequest {
    @NotBlank(message = "URL is required")
    private String url;
}
