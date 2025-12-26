package com.afklive.streamer.dto;

import lombok.Data;

@Data
public class StreamResponse {
    private String pid;
    private String streamKey;
    private String status;
    private String message;

    public StreamResponse(String pid, String streamKey, String status, String message) {
        this.pid = pid;
        this.streamKey = streamKey;
        this.status = status;
        this.message = message;
    }
}