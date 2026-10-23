package com.afklive.streamer.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** A destination a stream sends to, switchable per stream. */
@Entity
@Table(name = "stream_targets")
@IdClass(StreamTarget.Key.class)
@Data
@NoArgsConstructor
public class StreamTarget {

    @Id
    @Column(name = "stream_id")
    private Long streamId;

    @Id
    @Column(name = "destination_id")
    private Long destinationId;

    @Column(nullable = false)
    private boolean enabled = true;

    public StreamTarget(Long streamId, Long destinationId, boolean enabled) {
        this.streamId = streamId;
        this.destinationId = destinationId;
        this.enabled = enabled;
    }

    @Data
    @NoArgsConstructor
    public static class Key implements Serializable {
        private Long streamId;
        private Long destinationId;
    }
}
