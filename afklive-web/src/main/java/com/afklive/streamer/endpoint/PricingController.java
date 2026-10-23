package com.afklive.streamer.endpoint;

import com.afklive.streamer.service.BillingService;

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
    private final com.afklive.streamer.service.BillingService billing;

    /** Plans for the pricing page; the same limits and prices billing enforces (MK-2). */
    @GetMapping("/pricing")
    public ResponseEntity<?> getPricing(@RequestParam(defaultValue = "IN") String country) {
        String currency = "IN".equalsIgnoreCase(country) ? "INR" : "USD";
        List<Map<String, Object>> planList = new java.util.ArrayList<>();
        for (BillingService.PlanView p : billing.catalog(currency)) {
            List<String> features = new java.util.ArrayList<>();
            features.add(p.concurrentStreams() + (p.concurrentStreams() == 1 ? " stream" : " streams") + " at a time");
            features.add("Up to " + p.maxHeight() + "p" + p.maxFps());
            features.add(p.monthlyHours() > 0 ? p.monthlyHours() + " streaming hours a month" : "Unlimited streaming hours");
            features.add(formatStorage(p.storageBytes()) + " storage");
            features.add(p.destinationsPerStream() + (p.destinationsPerStream() == 1 ? " destination" : " destinations") + " per stream");
            if (p.watermark()) {
                features.add("Small AFK Live mark on the stream");
            }
            planList.add(Map.of("id", p.id(), "title", p.name(), "price", p.monthlyLabel(), "yearlyPrice", p.yearlyLabel(),
                    "period", p.monthly() == 0 ? "" : "/mo", "features", features));
        }
        return ResponseEntity.ok(Map.of("currency", currency, "plans", planList));
    }

    private String formatStorage(long bytes) {
        if (bytes >= 1024 * 1024 * 1024) {
            return (bytes / (1024 * 1024 * 1024)) + " GB";
        }
        return (bytes / (1024 * 1024)) + " MB";
    }

    // Plans change only through a verified payment (PaymentController callback) or an admin.
    // The old POST /api/pricing/upgrade changed the plan without any payment and was removed.

    /** Cancels at the end of the paid period (BI-5); access is kept until then. */
    @PostMapping("/pricing/cancel")
    public ResponseEntity<?> cancelPlan(Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        billing.cancel(SecurityUtils.getEmail(principal));
        return ResponseEntity.ok(Map.of("success", true, "message", "Your plan will end at the end of the period you paid for."));
    }
}
