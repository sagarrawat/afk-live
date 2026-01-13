package com.afklive.streamer.endpoint;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class PricingController {

    @GetMapping("/pricing")
    public ResponseEntity<?> getPricing(@RequestParam(defaultValue = "US") String country) {
        boolean isIndia = "IN".equalsIgnoreCase(country);

        // Tier 1: Free
        Map<String, Object> free = Map.of(
            "title", "Free",
            "price", isIndia ? "₹0" : "$0",
            "period", "/mo",
            "features", List.of("1 Channel", "10 Scheduled Posts", "Basic Streaming")
        );

        // Tier 2: Essentials
        Map<String, Object> essentials = Map.of(
            "title", "Essentials",
            "price", isIndia ? "₹499" : "$6",
            "period", "/mo",
            "features", List.of("3 Channels", "Unlimited Scheduling", "Analytics", "HD Streaming")
        );

        // Tier 3: Team
        Map<String, Object> team = Map.of(
            "title", "Team",
            "price", isIndia ? "₹999" : "$12",
            "period", "/mo",
            "features", List.of("Unlimited Channels", "Team Members", "Approval Workflow", "4K Streaming")
        );

        return ResponseEntity.ok(Map.of(
            "currency", isIndia ? "INR" : "USD",
            "plans", List.of(free, essentials, team)
        ));
    }
}
