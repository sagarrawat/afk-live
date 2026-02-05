package com.afklive.streamer.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class MergeRequest {
    @NotEmpty(message = "Files list cannot be empty")
    @Size(min = 2, message = "Select at least 2 videos to merge")
    private List<String> files;
}
