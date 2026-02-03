package com.afklive.streamer.endpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phonepe.sdk.pg.Env;
import com.phonepe.sdk.pg.payments.v2.StandardCheckoutClient;
import com.phonepe.sdk.pg.payments.v2.models.request.StandardCheckoutPayRequest;
import com.phonepe.sdk.pg.payments.v2.models.response.StandardCheckoutPayResponse;
import com.afklive.streamer.model.PaymentAudit;
import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.repository.PaymentAuditRepository;
import com.afklive.streamer.service.StripeService;
import com.afklive.streamer.service.UserService;
import com.afklive.streamer.util.SecurityUtils;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/payment")
@Slf4j
public class PaymentController {

    private final ObjectMapper objectMapper;
    private final StandardCheckoutClient phonePeClient;
    private final PaymentAuditRepository paymentAuditRepository;
    private final UserService userService;
    private final StripeService stripeService;
    private final String baseUrl;
    private final String stripeWebhookSecret;

    public PaymentController(
            PaymentAuditRepository paymentAuditRepository,
            UserService userService,
            StripeService stripeService,
            @Value("${app.phonepe.merchant-id}") String merchantId,
            @Value("${app.phonepe.salt-key}") String saltKey,
            @Value("${app.phonepe.salt-index}") Integer saltIndex,
            @Value("${app.phonepe.env:SANDBOX}") String envStr,
            @Value("${app.base-url}") String baseUrl,
            @Value("${app.stripe.webhook-secret:}") String stripeWebhookSecret) {
        this.objectMapper = new ObjectMapper();
        this.paymentAuditRepository = paymentAuditRepository;
        this.userService = userService;
        this.stripeService = stripeService;
        this.baseUrl = baseUrl;
        this.stripeWebhookSecret = stripeWebhookSecret;

        Env env = Env.valueOf(envStr.toUpperCase());
        this.phonePeClient = StandardCheckoutClient.getInstance(merchantId, saltKey, saltIndex, env);
        log.info("Initialized PhonePe Client: Merchant={}, Env={}", merchantId, env);
    }

    @PostMapping("/initiate")
    public ResponseEntity<?> initiatePayment(@RequestBody(required = false) Map<String, Object> body, Principal principal) {
        try {
            String planId = null;
            if (body != null && body.containsKey("planId")) {
                planId = body.get("planId").toString();
            }

            long amount = 100; // Default fallback
            if (planId != null) {
                try {
                    PlanType plan = PlanType.valueOf(planId);
                    if (plan == PlanType.ESSENTIALS) {
                        amount = 19900; // 199 INR
                    }
                } catch (IllegalArgumentException e) {
                    return ResponseEntity.badRequest().body(Map.of("message", "Invalid Plan ID"));
                }
            } else if (body != null && body.containsKey("amount")) {
                amount = Long.parseLong(body.get("amount").toString());
            }

            String merchantTransactionId = "MT" + UUID.randomUUID().toString().substring(0, 30).replace("-", "");
            String userId = (principal != null) ? principal.getName() : "test-user-" + UUID.randomUUID().toString().substring(0, 8);

            log.info("Initiating payment via SDK V2: TxnId={}, User={}, Plan={}", merchantTransactionId, userId, planId);

            // Audit
            PaymentAudit audit = new PaymentAudit(merchantTransactionId, userId, amount, "INITIATED");
            audit.setPlanId(planId);
            audit.setProvider("PHONEPE");
            paymentAuditRepository.save(audit);

            StandardCheckoutPayRequest payRequest = StandardCheckoutPayRequest.builder()
                    .merchantOrderId(merchantTransactionId)
                    .amount(amount)
                    .redirectUrl(this.baseUrl + "/studio?view=settings&payment_status=pending")
                    .build();

            StandardCheckoutPayResponse response = phonePeClient.pay(payRequest);
            String url = response.getRedirectUrl();

            return ResponseEntity.ok(Map.of("redirectUrl", url));

        } catch (Exception e) {
            log.error("Error initiating payment via SDK", e);
            return ResponseEntity.internalServerError().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/stripe/initiate")
    public ResponseEntity<?> initiateStripePayment(@RequestBody Map<String, Object> body, Principal principal) {
        try {
            String planId = (String) body.get("planId");
            if (planId == null) {
                return ResponseEntity.badRequest().body(Map.of("message", "Plan ID required"));
            }

            String userId = (principal != null) ? principal.getName() : "test-user-" + UUID.randomUUID().toString();
            String email = (principal != null) ? SecurityUtils.getEmail(principal) : "test@example.com";

            // Create Stripe Session
            Session session = stripeService.createCheckoutSession(planId, userId, email);

            if (session == null) {
                return ResponseEntity.badRequest().body(Map.of("message", "Failed to create session"));
            }

            // Audit
            PaymentAudit audit = new PaymentAudit(session.getId(), userId, session.getAmountTotal(), "INITIATED");
            audit.setPlanId(planId);
            audit.setProvider("STRIPE");
            paymentAuditRepository.save(audit);

            return ResponseEntity.ok(Map.of("redirectUrl", session.getUrl()));

        } catch (Exception e) {
            log.error("Error initiating stripe payment", e);
            return ResponseEntity.internalServerError().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/stripe/webhook")
    public ResponseEntity<?> handleStripeWebhook(@RequestBody String payload, @RequestHeader("Stripe-Signature") String sigHeader) {
        if (stripeWebhookSecret == null || stripeWebhookSecret.isBlank()) {
            return ResponseEntity.status(500).body("Webhook secret not configured");
        }

        try {
            Event event = Webhook.constructEvent(payload, sigHeader, stripeWebhookSecret);

            if ("checkout.session.completed".equals(event.getType())) {
                Session session = (Session) event.getDataObjectDeserializer().getObject().orElse(null);
                if (session != null) {
                    String sessionId = session.getId();
                    log.info("Stripe Session Completed: {}", sessionId);

                    paymentAuditRepository.findByTransactionId(sessionId).ifPresent(audit -> {
                        audit.setStatus("COMPLETED");
                        audit.setProviderReferenceId(session.getPaymentIntent());
                        audit.setRawResponse(payload);
                        paymentAuditRepository.save(audit);

                        if (audit.getPlanId() != null) {
                            try {
                                PlanType plan = PlanType.valueOf(audit.getPlanId());
                                userService.updatePlan(audit.getMerchantUserId(), plan);
                                log.info("Successfully upgraded user {} to plan {}", audit.getMerchantUserId(), plan);
                            } catch (Exception e) {
                                log.error("Failed to upgrade user plan after successful payment", e);
                            }
                        }
                    });
                }
            }

            return ResponseEntity.ok("Received");
        } catch (Exception e) {
            log.error("Stripe Webhook Error", e);
            return ResponseEntity.badRequest().body("Webhook Error: " + e.getMessage());
        }
    }

    @PostMapping("/callback")
    public ResponseEntity<?> handleCallback(@RequestBody String body, @RequestHeader(value = "X-VERIFY", required = false) String xVerify) {
        log.info("Payment Callback Received: {}", body);
        log.info("X-VERIFY: {}", xVerify);

        try {
             Map<String, Object> callbackData = objectMapper.readValue(body, Map.class);
             String encodedResponse = (String) callbackData.get("response");
             if(encodedResponse != null) {
                 byte[] decodedBytes = Base64.getDecoder().decode(encodedResponse);
                 String decodedJson = new String(decodedBytes, StandardCharsets.UTF_8);
                 log.info("Decoded Callback JSON: {}", decodedJson);

                 Map<String, Object> responseMap = objectMapper.readValue(decodedJson, Map.class);
                 Map<String, Object> data = (Map<String, Object>) responseMap.get("data");
                 if (data != null) {
                     String merchantTransactionId = (String) data.get("merchantTransactionId");
                     String state = (String) data.get("state"); // e.g. COMPLETED, FAILED
                     String providerReferenceId = (String) data.get("transactionId"); // PhonePe ID

                     if (merchantTransactionId != null) {
                         paymentAuditRepository.findByTransactionId(merchantTransactionId).ifPresent(audit -> {
                             audit.setStatus(state);
                             audit.setProviderReferenceId(providerReferenceId);
                             audit.setRawResponse(decodedJson);
                             paymentAuditRepository.save(audit);
                             log.info("Updated PaymentAudit for TxnId={}: Status={}", merchantTransactionId, state);

                             if ("COMPLETED".equals(state) && audit.getPlanId() != null) {
                                 try {
                                     PlanType plan = PlanType.valueOf(audit.getPlanId());
                                     userService.updatePlan(audit.getMerchantUserId(), plan);
                                     log.info("Successfully upgraded user {} to plan {}", audit.getMerchantUserId(), plan);
                                 } catch (Exception e) {
                                     log.error("Failed to upgrade user plan after successful payment", e);
                                 }
                             }
                         });
                     }
                 }
             }
        } catch (Exception e) {
            log.error("Error parsing callback", e);
        }

        return ResponseEntity.ok("Received");
    }
}
