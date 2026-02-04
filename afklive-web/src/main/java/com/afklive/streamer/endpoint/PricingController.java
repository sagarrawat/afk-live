package com.afklive.streamer.endpoint;

import com.afklive.streamer.model.PlanConfig;
import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.User;
import com.afklive.streamer.service.EmailService;
import com.afklive.streamer.service.PlanService;
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
    private final PlanService planService;

    @GetMapping("/pricing")
    public ResponseEntity<?> getPricing(@RequestParam(defaultValue = "IN") String country) {
        List<Map<String, Object>> planList = new java.util.ArrayList<>();
        List<PlanConfig> configs = planService.getAllPlans();

        for (PlanConfig p : configs) {
            String price = p.getPrice() != null ? p.getPrice() : "0";
            if (!price.startsWith("₹") && !price.startsWith("$") && !price.equals("Free")) {
                price = "₹" + price;
            }

            List<String> features = new java.util.ArrayList<>();
            features.add(p.getMaxChannels() + " Channels");

            if (p.getMaxScheduledPosts() == Integer.MAX_VALUE) {
                features.add("Unlimited Scheduling");
            } else {
                features.add(p.getMaxScheduledPosts() + " Scheduled Posts");
            }

            if (p.getMaxActiveStreams() == Integer.MAX_VALUE) {
                features.add("Unlimited Concurrent Streams");
            } else {
                features.add(p.getMaxActiveStreams() + " Concurrent Streams");
            }

            if (p.getMaxResolution() >= 1080) {
                features.add("HD Streaming");
            } else {
                features.add("Basic Streaming");
            }

            features.add("Storage: " + formatStorage(p.getMaxStorageBytes()));

            String cycle = p.getBillingCycle() != null ? "/" + p.getBillingCycle().toLowerCase() : "/mo";
            if (p.getBillingCycle() != null && (p.getBillingCycle().equalsIgnoreCase("Hourly") || p.getBillingCycle().equalsIgnoreCase("Hour"))) {
                 cycle = "/hr";
            }

            planList.add(Map.of(
                    "id", p.getPlanType().name(),
                    "title", p.getDisplayName(),
                    "price", price,
                    "period", cycle,
                    "features", features
            ));
        }

        // Sort ensuring FREE is first
        planList.sort((a, b) -> {
            if ("FREE".equals(a.get("id"))) return -1;
            if ("FREE".equals(b.get("id"))) return 1;
            return 0;
        });

        return ResponseEntity.ok(Map.of(
                "currency", "INR",
                "plans", planList
        ));
    }

    private String formatStorage(long bytes) {
        if (bytes >= 1024 * 1024 * 1024) {
            return (bytes / (1024 * 1024 * 1024)) + " GB";
        }
        return (bytes / (1024 * 1024)) + " MB";
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
