package com.afklive.streamer.endpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phonepe.sdk.pg.Env;
import com.phonepe.sdk.pg.payments.v2.StandardCheckoutClient;
import com.phonepe.sdk.pg.payments.v2.models.request.StandardCheckoutPayRequest;
import com.phonepe.sdk.pg.payments.v2.models.response.StandardCheckoutPayResponse;
import com.afklive.streamer.model.PaymentAudit;
import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.repository.PaymentAuditRepository;
import com.afklive.streamer.service.UserService;
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
    private final String baseUrl;
    private final String saltKey;
    private final Integer saltIndex;

    public PaymentController(
            PaymentAuditRepository paymentAuditRepository,
            UserService userService,
            @Value("${app.phonepe.merchant-id}") String merchantId,
            @Value("${app.phonepe.salt-key}") String saltKey,
            @Value("${app.phonepe.salt-index}") Integer saltIndex,
            @Value("${app.phonepe.env:SANDBOX}") String envStr,
            @Value("${app.base-url}") String baseUrl) {
        this.objectMapper = new ObjectMapper();
        this.paymentAuditRepository = paymentAuditRepository;
        this.userService = userService;
        this.baseUrl = baseUrl;
        this.saltKey = saltKey;
        this.saltIndex = saltIndex;

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
                if ("BALANCE_CLEAR".equals(planId)) {
                    if (body != null && body.containsKey("amount")) {
                        amount = Long.parseLong(body.get("amount").toString());
                        if (amount <= 0) {
                            return ResponseEntity.badRequest().body(Map.of("message", "Amount must be positive"));
                        }
                    } else {
                        return ResponseEntity.badRequest().body(Map.of("message", "Amount required for balance clear"));
                    }
                } else {
                    try {
                        PlanType plan = PlanType.valueOf(planId);
                        if (plan == PlanType.ESSENTIALS) {
                            amount = 19900; // 199 INR
                        }
                    } catch (IllegalArgumentException e) {
                        return ResponseEntity.badRequest().body(Map.of("message", "Invalid Plan ID"));
                    }
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

    @PostMapping("/callback")
    public ResponseEntity<?> handleCallback(@RequestBody String body, @RequestHeader(value = "X-VERIFY", required = false) String xVerify) {
        log.info("Payment Callback Received: length={}", body.length());

        try {
             Map<String, Object> callbackData = objectMapper.readValue(body, Map.class);
             String encodedResponse = (String) callbackData.get("response");

             if(encodedResponse != null && xVerify != null) {
                 // Checksum verification
                 // SHA256(base64Body + salt) + ### + saltIndex
                 String toHash = encodedResponse + saltKey;
                 java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                 byte[] hash = digest.digest(toHash.getBytes(StandardCharsets.UTF_8));
                 StringBuilder hexString = new StringBuilder();
                 for (byte b : hash) {
                     String hex = Integer.toHexString(0xff & b);
                     if (hex.length() == 1) hexString.append('0');
                     hexString.append(hex);
                 }
                 String calculatedChecksum = hexString.toString() + "###" + saltIndex;

                 if (!calculatedChecksum.equals(xVerify)) {
                     log.error("Checksum verification failed. Received: {}, Calculated: {}", xVerify, calculatedChecksum);
                     return ResponseEntity.badRequest().body("Invalid Checksum");
                 }

                 byte[] decodedBytes = Base64.getDecoder().decode(encodedResponse);
                 String decodedJson = new String(decodedBytes, StandardCharsets.UTF_8);

                 // Removed PII logging
                 // log.info("Decoded Callback JSON: {}", decodedJson);

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
                                 if ("BALANCE_CLEAR".equals(audit.getPlanId())) {
                                     double rupees = audit.getAmount() / 100.0;
                                     userService.clearUnpaidBalance(audit.getMerchantUserId(), rupees);
                                     log.info("Cleared balance: {} INR for {}", rupees, audit.getMerchantUserId());
                                 } else {
                                     try {
                                         PlanType plan = PlanType.valueOf(audit.getPlanId());
                                         userService.updatePlan(audit.getMerchantUserId(), plan);
                                         log.info("Successfully upgraded user {} to plan {}", audit.getMerchantUserId(), plan);
                                     } catch (Exception e) {
                                         log.error("Failed to upgrade user plan after successful payment", e);
                                     }
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
