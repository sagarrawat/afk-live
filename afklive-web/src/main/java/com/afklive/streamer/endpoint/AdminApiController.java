package com.afklive.streamer.endpoint;

import com.afklive.streamer.service.AppConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/settings")
@RequiredArgsConstructor
@Slf4j
public class AdminApiController {

    private final AppConfigService appConfigService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getSettings(@RequestParam(defaultValue = "INR") String currency) {
        return ResponseEntity.ok(Map.of(
                "hourlyRate", appConfigService.getCurrentHourlyRate(currency),
                "dailyBudgetCap", appConfigService.getDailyBudgetCap(currency),
                "globalMaxStreams", appConfigService.getGlobalStreamLimit(),
                "currency", currency
        ));
    }

    @PutMapping("/hourly-rate")
    public ResponseEntity<?> updateHourlyRate(@RequestBody Map<String, Object> payload) {
        String rateStr = payload.get("rate") != null ? payload.get("rate").toString() : null;
        String currency = payload.getOrDefault("currency", "INR").toString();
        
        if (rateStr == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Rate must be provided."));
        }
        
        BigDecimal rate = new BigDecimal(rateStr);
        if (rate.compareTo(BigDecimal.ZERO) < 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Rate must be a positive number."));
        }

        appConfigService.setCurrentHourlyRate(currency, rate);
        log.info("Admin updated hourly rate to {} {}", rate, currency);

        return ResponseEntity.ok(Map.of(
                "message", "Hourly rate updated to " + rate + " " + currency + ". Note: Applies to new sessions only.",
                "hourlyRate", rate,
                "currency", currency
        ));
    }

    @PutMapping("/daily-cap")
    public ResponseEntity<?> updateDailyCap(@RequestBody Map<String, Object> payload) {
        String capStr = payload.get("cap") != null ? payload.get("cap").toString() : null;
        String currency = payload.getOrDefault("currency", "INR").toString();
        
        if (capStr == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Cap must be provided."));
        }
        
        BigDecimal cap = new BigDecimal(capStr);
        if (cap.compareTo(BigDecimal.ZERO) < 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Cap must be a positive number."));
        }

        appConfigService.setDailyBudgetCap(currency, cap);
        log.info("Admin updated daily budget cap to {} {}", cap, currency);

        return ResponseEntity.ok(Map.of(
                "message", "Daily budget cap updated to " + cap + " " + currency,
                "dailyBudgetCap", cap,
                "currency", currency
        ));
    }

    @GetMapping("/daily-spend")
    public ResponseEntity<Map<String, Object>> getDailySpend(@RequestParam(defaultValue = "INR") String currency) {
        BigDecimal spend = appConfigService.getDailySpend(currency);
        BigDecimal cap = appConfigService.getDailyBudgetCap(currency);
        
        double percentUsed = 0;
        if (cap.compareTo(BigDecimal.ZERO) > 0) {
            percentUsed = spend.divide(cap, 4, RoundingMode.HALF_UP).doubleValue() * 100;
        }
        
        return ResponseEntity.ok(Map.of(
                "dailySpend", spend,
                "dailyBudgetCap", cap,
                "percentUsed", percentUsed,
                "currency", currency
        ));
    }
}
