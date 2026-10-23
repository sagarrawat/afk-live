package com.afklive.streamer.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Who an invoice is made out to (BI-3): name, address, GST state and optional GSTIN. */
@Entity
@Table(name = "billing_profiles")
@Data
@NoArgsConstructor
public class BillingProfile {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "legal_name", length = 200)
    private String legalName;

    @Column(name = "address_line", length = 400)
    private String addressLine;

    @Column(length = 100)
    private String city;

    /** GST state code, e.g. "29" for Karnataka; decides CGST+SGST vs IGST. */
    @Column(name = "state_code", length = 2)
    private String stateCode;

    @Column(name = "postal_code", length = 12)
    private String postalCode;

    @Column(nullable = false, length = 2)
    private String country = "IN";

    @Column(length = 15)
    private String gstin;
}
