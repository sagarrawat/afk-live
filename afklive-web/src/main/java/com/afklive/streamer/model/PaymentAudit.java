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

    @ManyToOne
    @JoinColumn(name = "user_id")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private User user;

    private String merchantUserId;

    private Long amount;

    private String status; // INITIATED, SUCCESS, FAILED

    private String providerReferenceId;

    @Column(columnDefinition = "TEXT")
    private String rawResponse;

    private String planId;

    /** NEW, UPGRADE, RENEW or BALANCE (009-billing). */
    @Column(length = 20)
    private String kind;
    @Column(name = "billing_cycle", length = 10)
    private String billingCycle;
    @Column(length = 3)
    private String currency;
    /** Proration credit (minor units) taken off this payment for an upgrade. */
    @Column(nullable = false)
    private long credit;
    /** BI-9: the launch offer this payment used, if any. */
    @Column(name = "offer_id")
    private Long offerId;
    /** BI-9: the plan's list price for the period (GST-inclusive, minor units), before any discount or credit. */
    @Column(name = "list_amount")
    private Long listAmount;
    /** BI-9: launch offer discount taken off the list price (GST-inclusive, minor units). */
    @Column(name = "discount_amount", nullable = false)
    private long discountAmount;

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
