package com.afklive.streamer.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "quota_audit")
public class QuotaAudit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String apiName;
    private int cost;
    private LocalDateTime timestamp;

    @ManyToOne
    @JoinColumn(name = "user_id")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private User user;

    private String status;

    public QuotaAudit(String apiName, int cost, User user, String status) {
        this.apiName = apiName;
        this.cost = cost;
        this.user = user;
        this.status = status;
        this.timestamp = LocalDateTime.now();
    }

    @com.fasterxml.jackson.annotation.JsonProperty("username")
    public String getUsername() {
        return user != null ? user.getUsername() : null;
    }
}
