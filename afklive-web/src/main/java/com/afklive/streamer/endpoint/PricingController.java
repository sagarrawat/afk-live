package com.afklive.streamer.endpoint;

import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.User;
import com.afklive.streamer.service.EmailService;
import com.afklive.streamer.service.UserService;
import com.afklive.streamer.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class PricingController {

    private final UserService userService;
    private final EmailService emailService;

    @GetMapping("/pricing")
    public ResponseEntity<?> getPricing(@RequestParam(defaultValue = "IN") String country) {
        boolean isIndia = "IN".equalsIgnoreCase(country);

        String currency = isIndia ? "INR" : "USD";
        String freePrice = isIndia ? "₹0" : "$0";
        String essentialsPrice = isIndia ? "₹199" : "$5";

        // Tier 1: Free
        Map<String, Object> free = Map.of(
            "id", "FREE",
            "title", "Free",
            "price", freePrice,
            "period", "/mo",
            "features", List.of("1 Channel", "10 Scheduled Posts", "Basic Streaming")
        );

        // Tier 2: Essentials
        Map<String, Object> essentials = Map.of(
            "id", "ESSENTIALS",
            "title", "Essentials",
            "price", essentialsPrice,
            "period", "/mo",
            "features", List.of("3 Channels", "Unlimited Scheduling", "Analytics", "HD Streaming")
        );

        return ResponseEntity.ok(Map.of(
            "currency", currency,
            "plans", List.of(free, essentials)
        ));
    }

    @PostMapping("/pricing/upgrade")
    public ResponseEntity<?> upgradePlan(@RequestBody Map<String, String> body, Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();

        String email = SecurityUtils.getEmail(principal);
        String planId = body.get("planId");
        log.info("Upgrade request for user {} to plan {}", email, planId);

        if (planId == null) return ResponseEntity.badRequest().body(Map.of("message", "Plan ID required"));

        try {
            PlanType plan = PlanType.valueOf(planId);
            userService.updatePlan(email, plan);
            log.info("Plan updated successfully for {}", email);

            // Send email
            try {
                 User user = userService.getOrCreateUser(email);
                 emailService.sendUpgradeEmail(user.getUsername(), plan.getDisplayName());
            } catch(Exception ex) {
                log.error("Failed to send upgrade email", ex);
                // log error but don't fail upgrade
            }

            return ResponseEntity.ok(Map.of("success", true, "message", "Plan upgraded to " + plan.getDisplayName()));
        } catch (IllegalArgumentException e) {
            log.error("Invalid plan ID provided: {}", planId);
            return ResponseEntity.badRequest().body(Map.of("message", "Invalid Plan ID"));
        } catch (Exception e) {
            log.error("Upgrade failed for user " + email, e);
            return ResponseEntity.internalServerError().body(Map.of("message", "Upgrade failed: " + e.getMessage()));
        }
    }

    @PostMapping("/pricing/cancel")
    public ResponseEntity<?> cancelPlan(Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        userService.updatePlan(SecurityUtils.getEmail(principal), PlanType.FREE);
        return ResponseEntity.ok(Map.of("success", true, "message", "Subscription cancelled. You are now on the Free plan."));
    }
}
