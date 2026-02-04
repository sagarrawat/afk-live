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
    private String username;
    private String status;

    public QuotaAudit(String apiName, int cost, String username, String status) {
        this.apiName = apiName;
        this.cost = cost;
        this.username = username;
        this.status = status;
        this.timestamp = LocalDateTime.now();
    }
}
