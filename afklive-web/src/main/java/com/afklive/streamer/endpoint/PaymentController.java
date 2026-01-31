package com.afklive.streamer.endpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phonepe.sdk.pg.Env;
import com.phonepe.sdk.pg.payments.v2.StandardCheckoutClient;
import com.phonepe.sdk.pg.payments.v2.models.request.StandardCheckoutPayRequest;
import com.phonepe.sdk.pg.payments.v2.models.response.StandardCheckoutPayResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
@Slf4j
public class PaymentController {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String MERCHANT_ID = "PGTESTPAYUAT";
    private static final String SALT_KEY = "099eb0cd-02cf-4e2a-8aca-3e6c6aff0399";
    private static final Integer SALT_INDEX = 1;
    private static final String CALLBACK_URL = "https://afklive.duckdns.org/api/payment/callback";

    // Initialize SDK Client
    // Note: Client Version is typically 1 unless specified otherwise
    private final StandardCheckoutClient phonePeClient = StandardCheckoutClient.getInstance(MERCHANT_ID, SALT_KEY, SALT_INDEX, Env.SANDBOX);

    @PostMapping("/initiate")
    public ResponseEntity<?> initiatePayment(@RequestBody(required = false) Map<String, Object> body, Principal principal) {
        try {
            long amount = 100; // 1.00 INR in paise
            if (body != null && body.containsKey("amount")) {
                amount = Long.parseLong(body.get("amount").toString());
            }

            String merchantTransactionId = "MT" + UUID.randomUUID().toString().substring(0, 30).replace("-", "");
            String userId = (principal != null) ? principal.getName() : "test-user-" + UUID.randomUUID().toString().substring(0, 8);

            log.info("Initiating payment via SDK V2: TxnId={}, User={}", merchantTransactionId, userId);

            StandardCheckoutPayRequest payRequest = StandardCheckoutPayRequest.builder()
                    .merchantOrderId(merchantTransactionId)
                    .amount(amount)
                    .redirectUrl("https://afklive.duckdns.org/pricing?payment=success")
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
             }
        } catch (Exception e) {
            log.error("Error parsing callback", e);
        }

        return ResponseEntity.ok("Received");
    }
}
