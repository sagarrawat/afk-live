package com.afklive.streamer.endpoint;

import com.afklive.streamer.model.TransactionLedger;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.service.LedgerService;
import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/stripe")
@Slf4j
public class StripeController {

    private final LedgerService ledgerService;
    private final UserRepository userRepository;
    private final com.afklive.streamer.service.UserService userService;
    private final String baseUrl;
    private final String webhookSecret;

    public StripeController(
            LedgerService ledgerService,
            UserRepository userRepository,
            com.afklive.streamer.service.UserService userService,
            @Value("${app.stripe.secret-key}") String secretKey,
            @Value("${app.stripe.webhook-secret}") String webhookSecret,
            @Value("${app.base-url}") String baseUrl) {
        this.ledgerService = ledgerService;
        this.userRepository = userRepository;
        this.userService = userService;
        this.baseUrl = baseUrl;
        this.webhookSecret = webhookSecret;
        Stripe.apiKey = secretKey;
    }

    @PostMapping("/create-checkout-session")
    public ResponseEntity<?> createCheckoutSession(@RequestBody Map<String, Object> request, Principal principal) {
        try {
            if (principal == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            User user = userRepository.findByEmail(principal.getName()).orElse(null);
            if (user == null) return ResponseEntity.notFound().build();

            long amount = 1000; // default $10.00
            if (request.containsKey("amount")) {
                double reqAmount = Double.parseDouble(request.get("amount").toString());
                amount = (long) (reqAmount * 100); // converting to cents
            }

            SessionCreateParams params = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.PAYMENT)
                    .setSuccessUrl(baseUrl + "/settings?payment=success")
                    .setCancelUrl(baseUrl + "/settings?payment=cancelled")
                    .setClientReferenceId(user.getId().toString())
                    .addLineItem(
                            SessionCreateParams.LineItem.builder()
                                    .setQuantity(1L)
                                    .setPriceData(
                                            SessionCreateParams.LineItem.PriceData.builder()
                                                    .setCurrency("usd")
                                                    .setUnitAmount(amount)
                                                    .setProductData(
                                                            SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                                                    .setName("AFK Live Credit Top-Up")
                                                                    .build())
                                                    .build())
                                    .build())
                    .build();

            Session session = Session.create(params);

            return ResponseEntity.ok(Map.of("sessionId", session.getId(), "redirectUrl", session.getUrl()));
        } catch (Exception e) {
            log.error("Stripe session creation failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/webhook")
    public ResponseEntity<String> handleWebhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String sigHeader) {
        
        Event event;
        try {
            event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            log.warn("Invalid Stripe signature");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid signature");
        } catch (Exception e) {
            log.error("Error parsing webhook", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Bad payload");
        }

        if ("checkout.session.completed".equals(event.getType())) {
            Session session = (Session) event.getDataObjectDeserializer().getObject().orElse(null);
            if (session != null && "paid".equals(session.getPaymentStatus())) {
                String userIdStr = session.getClientReferenceId();
                if (userIdStr != null) {
                    Optional<User> userOpt = userRepository.findById(Long.parseLong(userIdStr));
                    if (userOpt.isPresent()) {
                        BigDecimal depositAmount = BigDecimal.valueOf(session.getAmountTotal()).divide(BigDecimal.valueOf(100));
                        
                        // 1. Deduct the amount from unpaid balance (this increases their credit)
                        userService.clearUnpaidBalance(userOpt.get().getEmail(), depositAmount);
                        
                        // 2. Refresh user to get latest balance for ledger
                        User refreshedUser = userRepository.findById(userOpt.get().getId()).orElse(userOpt.get());
                        
                        // 3. Record transaction
                        ledgerService.recordTransaction(
                                refreshedUser,
                                depositAmount,
                                TransactionLedger.TransactionType.DEPOSIT,
                                session.getId(),
                                "Stripe Top-Up"
                        );
                        log.info("Successfully recorded Stripe deposit for user {}: ${}", userIdStr, depositAmount);
                    }
                }
            }
        }

        return ResponseEntity.ok("Success");
    }
}
