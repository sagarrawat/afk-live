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

/** One browser or installed app that gets alerts as push notifications (NT-1, MB-2). */
@Entity
@Table(name = "push_subscriptions")
@Data
@NoArgsConstructor
public class PushSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** The push service URL for this browser (FCM, Mozilla, Apple, Microsoft). */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String endpoint;

    /** The browser's public key (base64url, uncompressed P-256 point). */
    @Column(nullable = false)
    private String p256dh;

    /** The browser's auth secret (base64url, 16 bytes). */
    @Column(nullable = false)
    private String auth;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt;

    @Column(name = "last_sent_at")
    private ZonedDateTime lastSentAt;
}
