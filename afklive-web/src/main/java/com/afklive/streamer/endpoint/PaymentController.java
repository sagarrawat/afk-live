package com.afklive.streamer.endpoint;

import com.afklive.streamer.payment.PaymentGateway;
import com.afklive.streamer.payment.PaymentService;
import com.afklive.streamer.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
@Slf4j
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentGateway paymentGateway;

    /** Starts a checkout for a plan or for the user's outstanding balance. The amount is set server-side. */
    @PostMapping("/initiate")
    public ResponseEntity<?> initiatePayment(@RequestBody(required = false) Map<String, Object> body, Principal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Please sign in to continue."));
        }
        String planId = (body != null && body.get("planId") != null) ? body.get("planId").toString() : null;
        if (planId == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Plan ID required"));
        }
        try {
            String redirectUrl = paymentService.startCheckout(SecurityUtils.getEmail(principal), planId);
            return ResponseEntity.ok(Map.of("redirectUrl", redirectUrl));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Error starting checkout", e);
            return ResponseEntity.internalServerError().body(Map.of("message", "Could not start the payment. Please try again."));
        }
    }

    /**
     * Called by the app when the user returns from checkout. Checks the order with the provider,
     * so upgrades work even if the provider's callback is delayed or not configured.
     */
    @GetMapping("/status")
    public ResponseEntity<?> paymentStatus(@RequestParam("orderId") String orderId, Principal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }
        String status = paymentService.confirmForUser(orderId, SecurityUtils.getEmail(principal));
        return ResponseEntity.ok(Map.of("status", status));
    }

    /** Server-to-server notification from the payment provider; authenticated by the provider's signature. */
    @PostMapping("/callback")
    public ResponseEntity<?> handleCallback(@RequestBody String body,
                                            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String orderId;
        try {
            orderId = paymentGateway.verifyCallback(authorization, body);
        } catch (SecurityException e) {
            log.warn("Rejected payment callback: {}", e.getMessage());
            return ResponseEntity.status(401).body("Unauthorized");
        }
        try {
            String status = paymentService.confirm(orderId);
            log.info("Payment callback for {} -> {}", orderId, status);
        } catch (IllegalArgumentException e) {
            log.warn("Payment callback for unknown order {}", orderId);
        }
        return ResponseEntity.ok("Received");
    }
}
