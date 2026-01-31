package com.afklive.streamer.endpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phonepe.sdk.pg.Env;
import com.phonepe.sdk.pg.payments.v2.StandardCheckoutClient;
import com.phonepe.sdk.pg.payments.v2.models.request.StandardCheckoutPayRequest;
import com.phonepe.sdk.pg.payments.v2.models.response.StandardCheckoutPayResponse;
import com.afklive.streamer.model.PaymentAudit;
import com.afklive.streamer.repository.PaymentAuditRepository;
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

    public PaymentController(
            PaymentAuditRepository paymentAuditRepository,
            @Value("${app.phonepe.merchant-id}") String merchantId,
            @Value("${app.phonepe.salt-key}") String saltKey,
            @Value("${app.phonepe.salt-index}") Integer saltIndex,
            @Value("${app.phonepe.env:SANDBOX}") String envStr,
            @Value("${app.base-url}") String baseUrl) {
        this.objectMapper = new ObjectMapper();
        this.paymentAuditRepository = paymentAuditRepository;

        Env env = Env.valueOf(envStr.toUpperCase());
        this.phonePeClient = StandardCheckoutClient.getInstance(merchantId, saltKey, saltIndex, env);
        log.info("Initialized PhonePe Client: Merchant={}, Env={}", merchantId, env);
    }

    @PostMapping("/initiate")
    public ResponseEntity<?> initiatePayment(@RequestBody Map<String, Object> body, Principal principal) {
        if (principal == null) return ResponseEntity.status(401).body(Map.of("message", "Unauthorized"));
        String email = principal.getName(); // Email is username

        try {
            long amount = 100; // 1.00 INR in paise
            if (body != null && body.containsKey("amount")) {
                amount = Long.parseLong(body.get("amount").toString());
            }

            String merchantTransactionId = "MT" + UUID.randomUUID().toString().substring(0, 30).replace("-", "");
            // Store planId in userId field or custom param if supported? PhonePe passes back merchantUserId.
            // We can encode planId in the transaction ID or store it in a DB.
            // For simplicity, we'll assume the callback updates to "ESSENTIALS" if amount matches 499.

            // Redirect to Settings page
            String redirectUrl = "https://afklive.duckdns.org/studio?view=settings&payment_status=pending&txnId=" + merchantTransactionId;

            log.info("Initiating payment via SDK V2: TxnId={}, User={}", merchantTransactionId, userId);

            // Audit
            PaymentAudit audit = new PaymentAudit(merchantTransactionId, userId, amount, "INITIATED");
            paymentAuditRepository.save(audit);

            StandardCheckoutPayRequest payRequest = StandardCheckoutPayRequest.builder()
                    .merchantOrderId(merchantTransactionId)
                    .amount(amount)
                    .redirectUrl("https://afklive.in/app?payment=success")
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
