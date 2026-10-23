package com.afklive.streamer.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

/** RE-7: when a platform component was last seen working (e.g. the ingest server calling its auth hook). */
@Entity
@Table(name = "status_signals")
@Data
@NoArgsConstructor
public class StatusSignal {
    @Id
    @Column(length = 40)
    private String name;
    @Column(name = "seen_at", nullable = false)
    private ZonedDateTime seenAt;
}
