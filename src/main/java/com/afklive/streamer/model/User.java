package com.afklive.streamer.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@Table(name = "users")
public class User {
    @Id
    private String username; // Email from OAuth

    @Enumerated(EnumType.STRING)
    private PlanType planType = PlanType.FREE;

    private long usedStorageBytes = 0;

    public User(String username) {
        this.username = username;
    }
}
