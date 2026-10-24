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

/** OPS-1: one email we tried to send. Never the body or the address. */
@Entity
@Table(name = "email_log")
@Data
@NoArgsConstructor
public class EmailLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt;

    /** VERIFY, RESET, WELCOME, ALERT, ... (see EmailService.Kind). */
    @Column(nullable = false, length = 30)
    private String kind;

    /** The recipient's account; null for mail to the support inbox or an address with no account. */
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false)
    private boolean ok;

    /** Why it wasn't sent, in short. */
    @Column(length = 300)
    private String error;
}
