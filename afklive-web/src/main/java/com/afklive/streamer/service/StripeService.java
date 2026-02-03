package com.afklive.streamer.service;

import com.afklive.streamer.model.PlanType;
import com.stripe.Stripe;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

@Service
@Slf4j
public class StripeService {

    @Value("${app.stripe.secret-key}")
    private String secretKey;

    @Value("${app.base-url}")
    private String baseUrl;

    @PostConstruct
    public void init() {
        if (secretKey != null && !secretKey.isBlank()) {
            Stripe.apiKey = secretKey;
            log.info("Stripe initialized");
        } else {
            log.warn("Stripe secret key not found");
        }
    }

    public Session createCheckoutSession(String planId, String userId, String userEmail) throws Exception {
        PlanType plan = PlanType.valueOf(planId);
        long priceInCents = 0;

        // Define pricing logic here
        if (plan == PlanType.ESSENTIALS) {
            priceInCents = 500; // $5.00
        } else if (plan == PlanType.TEAM) {
             priceInCents = 2000; // $20.00
        } else {
             // Default fallback or free
             if (plan == PlanType.FREE) return null;
             throw new IllegalArgumentException("Invalid plan for payment: " + planId);
        }

        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(baseUrl + "/studio?view=settings&payment_status=success&session_id={CHECKOUT_SESSION_ID}")
                .setCancelUrl(baseUrl + "/pricing?payment_status=cancelled")
                .setCustomerEmail(userEmail)
                .setClientReferenceId(userId)
                .putMetadata("planId", planId)
                .putMetadata("userId", userId)
                .addLineItem(
                        SessionCreateParams.LineItem.builder()
                                .setQuantity(1L)
                                .setPriceData(
                                        SessionCreateParams.LineItem.PriceData.builder()
                                                .setCurrency("usd")
                                                .setUnitAmount(priceInCents)
                                                .setProductData(
                                                        SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                                                .setName(plan.getDisplayName() + " Plan")
                                                                .build()
                                                )
                                                .build()
                                )
                                .build()
                )
                .build();

        return Session.create(params);
    }
}
