package com.afklive.streamer.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "payment_audits", indexes = {
    @Index(name = "idx_payment_txn_id", columnList = "transactionId", unique = true)
})
@Data
@NoArgsConstructor
public class PaymentAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String transactionId;

    private String merchantUserId;

    private Long amount;

    private String status; // INITIATED, SUCCESS, FAILED

    private String providerReferenceId;

    @Column(columnDefinition = "TEXT")
    private String rawResponse;

    private String planId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public PaymentAudit(String transactionId, String merchantUserId, Long amount, String status) {
        this.transactionId = transactionId;
        this.merchantUserId = merchantUserId;
        this.amount = amount;
        this.status = status;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
