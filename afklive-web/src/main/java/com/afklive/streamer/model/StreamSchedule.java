package com.afklive.streamer.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;

/**
 * When a stream goes live on its own: once (start and optional end), or daily / weekly between a
 * start and an end time in the creator's time zone, with dates to skip.
 */
@Entity
@Table(name = "stream_schedules")
@Data
@NoArgsConstructor
public class StreamSchedule {

    public enum Kind { ONCE, DAILY, WEEKLY }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stream_id", nullable = false)
    private Long streamId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    /** ONCE */
    private ZonedDateTime startAt;
    private ZonedDateTime endAt;

    /** DAILY / WEEKLY: comma-separated MON..SUN (WEEKLY only), local start and end times. */
    private String daysOfWeek;
    private LocalTime startTime;
    private LocalTime endTime;

    @Column(nullable = false, length = 64)
    private String timeZone = "UTC";

    /** Comma-separated yyyy-MM-dd dates to skip. */
    @Column(columnDefinition = "TEXT")
    private String skipDates;

    @Column(nullable = false)
    private boolean enabled = true;

    /** Local date of the last occurrence started (so each occurrence starts once). */
    private LocalDate lastStartedOn;

    @Column(columnDefinition = "TEXT")
    private String lastError;

    /** SC-4: this slot's videos, as media ids "12,7,9"; null plays the stream's playlist. */
    @Column(columnDefinition = "TEXT")
    private String playlist;

    @Column(nullable = false)
    private ZonedDateTime createdAt = ZonedDateTime.now();
}
