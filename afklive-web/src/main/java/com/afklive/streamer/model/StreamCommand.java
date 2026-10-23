package com.afklive.streamer.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

/** A live change for the worker running a job: switch scene, toggle an overlay, skip, reload the playlist. */
@Entity
@Table(name = "stream_commands")
@Data
@NoArgsConstructor
public class StreamCommand {

    /** TARGETS: the run's destination list changed (payload: the spec's destinations); applied by the worker's runner. RECONNECT: redial every destination (GL-5), also the runner's. */
    public enum Type { SCENE, OVERLAY, SKIP, RELOAD, FINISH_PASS, TARGETS, RECONNECT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Column(columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false)
    private ZonedDateTime createdAt = ZonedDateTime.now();

    public StreamCommand(Long jobId, Type type, String payload) {
        this.jobId = jobId;
        this.type = type;
        this.payload = payload;
    }
}
