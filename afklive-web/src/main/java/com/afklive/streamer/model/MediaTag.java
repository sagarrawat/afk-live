package com.afklive.streamer.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * ML-8: a creator's own label for library files ("Shows" or moods like "Rainy"), shown as "Tags" in
 * the app. Not the YouTube upload {@link Tag}. Names are unique per user, ignoring case.
 */
@Entity
@Table(name = "media_tags")
@Data
@NoArgsConstructor
public class MediaTag {

    public static final int MAX_NAME = 40;
    public static final int MAX_PER_USER = 50;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = MAX_NAME)
    private String name;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public MediaTag(Long userId, String name) {
        this.userId = userId;
        this.name = name;
    }
}
