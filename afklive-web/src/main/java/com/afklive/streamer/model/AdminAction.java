package com.afklive.streamer.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

/** AD-3: every admin change, who made it and why. */
@Entity
@Table(name = "admin_actions")
@Data
@NoArgsConstructor
public class AdminAction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "admin_email", nullable = false)
    private String adminEmail;
    @Column(nullable = false, length = 40)
    private String action;
    @Column(name = "target_type", nullable = false, length = 20)
    private String targetType;
    @Column(name = "target_id", nullable = false, length = 64)
    private String targetId;
    @Column(columnDefinition = "TEXT")
    private String detail;
    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt;
}
