package com.afklive.streamer.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

/** A tax invoice for one payment (BI-3, BI-6). Amounts in the currency's minor unit; never edited after issue. */
@Entity
@Table(name = "invoices")
@Data
@NoArgsConstructor
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String number;

    @Column(name = "user_id")
    @JsonIgnore
    private Long userId;

    @Column(name = "payment_order_id", unique = true)
    @JsonIgnore
    private String paymentOrderId;

    @Column(nullable = false, length = 300)
    private String description;

    private ZonedDateTime periodStart;
    private ZonedDateTime periodEnd;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "taxable_amount", nullable = false)
    private long taxableAmount;
    private long cgst;
    private long sgst;
    private long igst;
    @Column(nullable = false)
    private long total;
    /** BI-9: list price (GST-inclusive) before a discount; null when there was none. */
    @Column(name = "list_amount")
    private Long listAmount;
    /** BI-9: discount (GST-inclusive) taken off the list price; the taxable value is worked out after it. */
    @Column(name = "discount_amount", nullable = false)
    private long discountAmount;
    @Column(name = "discount_label", length = 120)
    private String discountLabel;

    private String customerName;
    @Column(columnDefinition = "TEXT")
    private String customerAddress;
    private String customerState;
    private String customerGstin;

    @Column(name = "issued_at", nullable = false)
    private ZonedDateTime issuedAt;
}
