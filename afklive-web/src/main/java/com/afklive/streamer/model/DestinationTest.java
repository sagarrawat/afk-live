package com.afklive.streamer.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

/** One "Test" of a destination: requested on the web, run on a worker. */
@Entity
@Table(name = "destination_tests")
@Data
@NoArgsConstructor
public class DestinationTest {

    public enum Status { PENDING, RUNNING, OK, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    @JsonIgnore
    private Long userId;

    @Column(name = "destination_id")
    private Long destinationId;

    /** The destination's address and key at the time of the test (encrypted). */
    @Column(nullable = false, columnDefinition = "TEXT")
    @Convert(converter = com.afklive.streamer.util.EncryptedStringConverter.class)
    @JsonIgnore
    private String url;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(name = "worker_id", length = 100)
    @JsonIgnore
    private String workerId;

    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt = ZonedDateTime.now();

    private ZonedDateTime startedAt;
    private ZonedDateTime finishedAt;
}
