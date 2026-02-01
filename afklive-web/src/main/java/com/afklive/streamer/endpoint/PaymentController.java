package com.afklive.streamer.endpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
@Slf4j
public class PaymentController {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();
    private final com.afklive.streamer.service.UserService userService;

    @Value("${app.phonepe.merchant-id}")
    private String merchantId;
    @Value("${app.phonepe.salt-key}")
    private String saltKey;
    @Value("${app.phonepe.salt-index}")
    private int saltIndex;
    @Value("${app.phonepe.salt-env}")
    private String saltEnv;

    private static final String CALLBACK_URL = "https://afklive.duckdns.org/api/payment/callback";

    @PostMapping("/initiate")
    public ResponseEntity<?> initiatePayment(@RequestBody Map<String, Object> body, Principal principal) {
        if (principal == null) return ResponseEntity.status(401).body(Map.of("message", "Unauthorized"));
        String email = principal.getName();

        try {
            String planId = (String) body.get("planId");
            // Default to ESSENTIALS if not provided, or handle error
            if (planId == null || !"ESSENTIALS".equals(planId)) {
                 // For now, we only support ESSENTIALS in this flow
                 // return ResponseEntity.badRequest().body(Map.of("message", "Invalid Plan"));
                 planId = "ESSENTIALS";
            }

            long amount = 19900; // 199.00 INR

            // Generate Txn ID
            String merchantTransactionId = "MT" + UUID.randomUUID().toString().substring(0, 30).replace("-", "");

            // Redirect URL (Success/Failure)
            String redirectUrl = "https://afklive.duckdns.org/studio?view=settings&payment_status=pending&txnId=" + merchantTransactionId;

            Map<String, Object> payload = new HashMap<>();
            payload.put("merchantId", merchantId);
            payload.put("merchantTransactionId", merchantTransactionId);
            payload.put("merchantUserId", email);
            payload.put("amount", amount);
            payload.put("redirectUrl", redirectUrl);
            payload.put("redirectMode", "REDIRECT");
            payload.put("callbackUrl", CALLBACK_URL);
            payload.put("mobileNumber", "9999999999");

            Map<String, Object> paymentInstrument = new HashMap<>();
            paymentInstrument.put("type", "PAY_PAGE");
            payload.put("paymentInstrument", paymentInstrument);

            String jsonPayload = objectMapper.writeValueAsString(payload);
            String base64Payload = Base64.getEncoder().encodeToString(jsonPayload.getBytes(StandardCharsets.UTF_8));

            String stringToHash = base64Payload + "/pg/v1/pay" + saltKey;
            String checksum = sha256(stringToHash) + "###" + saltIndex;

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-VERIFY", checksum);

            Map<String, String> requestBody = new HashMap<>();
            requestBody.put("request", base64Payload);

            HttpEntity<Map<String, String>> requestEntity = new HttpEntity<>(requestBody, headers);

            log.info("Initiating payment: TxnId={}, User={}", merchantTransactionId, email);

            String targetUrl = saltEnv + "/pg/v1/pay";
            ResponseEntity<Map> response = restTemplate.postForEntity(targetUrl, requestEntity, Map.class);
            Map<String, Object> responseBody = response.getBody();

            if (responseBody != null && Boolean.TRUE.equals(responseBody.get("success"))) {
                Map<String, Object> data = (Map<String, Object>) responseBody.get("data");
                Map<String, Object> instrumentResponse = (Map<String, Object>) data.get("instrumentResponse");
                Map<String, Object> redirectInfo = (Map<String, Object>) instrumentResponse.get("redirectInfo");
                String url = (String) redirectInfo.get("url");

                return ResponseEntity.ok(Map.of("redirectUrl", url));
            } else {
                log.error("Payment initiation failed: {}", responseBody);
                return ResponseEntity.badRequest().body(Map.of("message", "Payment initiation failed", "details", responseBody));
            }

        } catch (Exception e) {
            log.error("Error initiating payment", e);
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
                 String code = (String) responseMap.get("code");

                 if ("PAYMENT_SUCCESS".equals(code)) {
                     Map<String, Object> data = (Map<String, Object>) responseMap.get("data");
                     String merchantUserId = (String) data.get("merchantUserId");
                     Number amountNum = (Number) data.get("amount");
                     long amount = amountNum.longValue();

                     if (amount == 19900) {
                         userService.updatePlan(merchantUserId, com.afklive.streamer.model.PlanType.ESSENTIALS);
                     }
                 }
             }
        } catch (Exception e) {
            log.error("Error parsing callback", e);
        }

        return ResponseEntity.ok("Received");
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
