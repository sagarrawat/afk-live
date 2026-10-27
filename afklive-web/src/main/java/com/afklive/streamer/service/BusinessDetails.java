package com.afklive.streamer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The business details the public contact page shows (Consumer Protection (E-Commerce) Rules 2020,
 * rule 4(2) and 4(4); IT Rules 2021, rule 3(2)): legal name, registered address and GSTIN (the same
 * settings as the GST invoices) and the grievance officer.
 * <p>
 * Every detail is optional. One that's empty, or still looks like a placeholder ("XXXX", "TODO",
 * "changeme", an example.com or test@test.com address), is treated as not set, so the page leaves its
 * line out (the grievance officer falls back to the support address) and a warning is logged at startup.
 */
@Component
@Slf4j
public class BusinessDetails {

    /** The address the whole public site already shows for support (footer, terms, structured data). */
    public static final String SUPPORT_EMAIL = "support@afklive.in";

    private static final Pattern PLACEHOLDER = Pattern.compile(
            "(?i)x{3,}|\\btodo\\b|\\btbd\\b|change ?me|placeholder|lorem|example\\.(com|org|net)|test@test\\.com|<[^>]*>|\\$\\{");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern GSTIN = Pattern.compile("\\d{2}[A-Z]{5}\\d{4}[A-Z][1-9A-Z]Z[0-9A-Z]");

    private final Optional<String> name;
    private final Optional<String> address;
    private final Optional<String> gstin;
    private final Optional<String> officerName;
    private final Optional<String> officerEmail;
    private final String role;

    public BusinessDetails(@Value("${app.billing.seller-name:AFK Live}") String name,
                           @Value("${app.billing.seller-address:}") String address,
                           @Value("${app.billing.seller-gstin:}") String gstin,
                           @Value("${app.grievance.officer-name:}") String officerName,
                           @Value("${app.grievance.officer-email:}") String officerEmail,
                           @Value("${app.streaming.role:all}") String role) {
        this.name = text(name);
        this.address = text(address).map(a -> a.replaceAll("\\s*[\\r\\n]+\\s*", ", "));
        this.gstin = text(gstin).map(g -> g.toUpperCase(Locale.ROOT)).filter(g -> GSTIN.matcher(g).matches());
        this.officerName = text(officerName);
        this.officerEmail = text(officerEmail).filter(e -> EMAIL.matcher(e).matches());
        this.role = role;
    }

    private static Optional<String> text(String value) {
        if (value == null || value.isBlank() || PLACEHOLDER.matcher(value).find()) {
            return Optional.empty();
        }
        return Optional.of(value.strip());
    }

    /** Legal name (APP_BILLING_SELLER_NAME). */
    public Optional<String> name() {
        return name;
    }

    /** Registered address on one line (APP_BILLING_SELLER_ADDRESS). */
    public Optional<String> address() {
        return address;
    }

    /** GSTIN, only when it's a well-formed one (APP_BILLING_SELLER_GSTIN). */
    public Optional<String> gstin() {
        return gstin;
    }

    /** Grievance officer's name (APP_GRIEVANCE_OFFICER_NAME). */
    public Optional<String> officerName() {
        return officerName;
    }

    /** Grievance officer's email (APP_GRIEVANCE_OFFICER_EMAIL). */
    public Optional<String> officerEmail() {
        return officerEmail;
    }

    /** What's missing from the contact page, in words, for the startup warning. Empty when all is set. */
    public List<String> missing() {
        List<String> out = new ArrayList<>();
        if (officerName.isEmpty()) {
            out.add("APP_GRIEVANCE_OFFICER_NAME");
        }
        if (officerEmail.isEmpty()) {
            out.add("APP_GRIEVANCE_OFFICER_EMAIL");
        }
        if (address.isEmpty()) {
            out.add("APP_BILLING_SELLER_ADDRESS");
        }
        return out;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warnIfIncomplete() {
        if ("worker".equalsIgnoreCase(role)) {
            return;
        }
        List<String> missing = missing();
        if (!missing.isEmpty()) {
            log.warn("The public contact page (/contact) is missing {} (empty, malformed or a placeholder). Those lines are left out; "
                    + "grievances go to {} until a grievance officer is set. Indian e-commerce rules expect the officer's name and "
                    + "contact and the business address to be shown.", String.join(", ", missing), SUPPORT_EMAIL);
        }
    }
}
