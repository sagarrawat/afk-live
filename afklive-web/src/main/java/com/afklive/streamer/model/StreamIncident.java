package com.afklive.streamer.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

/** One outage of a run (or of one destination of it), from when it was alerted until it recovered. */
@Entity
@Table(name = "stream_incidents")
@Data
@NoArgsConstructor
public class StreamIncident {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Column(nullable = false, length = 30)
    private String kind;

    @Column(nullable = false, length = 100)
    private String target = "";

    @Column(name = "started_at", nullable = false)
    private ZonedDateTime startedAt;

    @Column(name = "resolved_at")
    private ZonedDateTime resolvedAt;
}
