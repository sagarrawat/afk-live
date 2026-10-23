package com.afklive.streamer.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

/** RE-7: one timestamped update on a status incident. */
@Entity
@Table(name = "status_incident_updates")
@Data
@NoArgsConstructor
public class StatusIncidentUpdate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "incident_id", nullable = false)
    private Long incidentId;
    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private StatusIncident.Status status;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;
    @Column(name = "created_by", nullable = false)
    private String createdBy;
    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt;
}
