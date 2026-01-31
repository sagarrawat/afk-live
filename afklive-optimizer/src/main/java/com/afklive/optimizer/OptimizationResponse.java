package com.afklive.optimizer;

import com.fasterxml.jackson.annotation.JsonProperty;

public class OptimizationResponse {
    private String status;

    @JsonProperty("original_key")
    private String originalKey;

    @JsonProperty("optimized_key")
    private String optimizedKey;

    @JsonProperty("file_size")
    private Long fileSize;

    private String message;

    public OptimizationResponse() {
    }

    public OptimizationResponse(String status, String originalKey, String optimizedKey, Long fileSize, String message) {
        this.status = status;
        this.originalKey = originalKey;
        this.optimizedKey = optimizedKey;
        this.fileSize = fileSize;
        this.message = message;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getOriginalKey() {
        return originalKey;
    }

    public void setOriginalKey(String originalKey) {
        this.originalKey = originalKey;
    }

    public String getOptimizedKey() {
        return optimizedKey;
    }

    public void setOptimizedKey(String optimizedKey) {
        this.optimizedKey = optimizedKey;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
