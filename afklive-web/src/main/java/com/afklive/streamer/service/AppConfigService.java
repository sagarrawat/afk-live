package com.afklive.streamer.service;

import com.afklive.streamer.model.AppSetting;
import com.afklive.streamer.repository.AppSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Central service for all dynamic platform configuration stored in the app_settings table.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AppConfigService {

    private final AppSettingRepository appSettingRepository;

    // ── Setting Keys ──────────────────────────────────────────────────────────
    private static final String KEY_GLOBAL_MAX_STREAMS    = "global_max_streams";
    private static final String KEY_HOURLY_RATE_USD       = "billing_hourly_rate_usd";
    private static final String KEY_HOURLY_RATE_INR       = "billing_hourly_rate_inr";
    private static final String KEY_DAILY_BUDGET_CAP_USD  = "billing_daily_budget_cap_usd";
    private static final String KEY_DAILY_BUDGET_CAP_INR  = "billing_daily_budget_cap_inr";
    private static final String KEY_DAILY_SPEND_AMOUNT    = "billing_daily_spend_amount_"; // appended with currency
    private static final String KEY_DAILY_SPEND_DATE      = "billing_daily_spend_date";
    private static final String KEY_TAX_RATE_PREFIX       = "tax_rate_";

    // ── Defaults ──────────────────────────────────────────────────────────────
    private static final int        DEFAULT_MAX_STREAMS   = 100;
    private static final BigDecimal DEFAULT_HOURLY_RATE_USD = new BigDecimal("0.08");
    private static final BigDecimal DEFAULT_HOURLY_RATE_INR = new BigDecimal("5.00");
    private static final BigDecimal DEFAULT_DAILY_CAP_USD   = new BigDecimal("50.00");
    private static final BigDecimal DEFAULT_DAILY_CAP_INR   = new BigDecimal("4000.00");
    private static final BigDecimal DEFAULT_TAX_RATE        = BigDecimal.ZERO;

    // ── Global Stream Limit ───────────────────────────────────────────────────

    @org.springframework.cache.annotation.Cacheable(value = "appSettings", key = "'global_max_streams'")
    public int getGlobalStreamLimit() {
        return getIntSetting(KEY_GLOBAL_MAX_STREAMS, DEFAULT_MAX_STREAMS);
    }

    @org.springframework.cache.annotation.CacheEvict(value = "appSettings", key = "'global_max_streams'")
    public void setGlobalStreamLimit(int limit) {
        saveSetting(KEY_GLOBAL_MAX_STREAMS, String.valueOf(Math.max(0, limit)));
    }

    // ── Tax Rates ─────────────────────────────────────────────────────────────
    public BigDecimal getTaxRate(String region) {
        if (region == null || region.isEmpty()) return DEFAULT_TAX_RATE;
        return getBigDecimalSetting(KEY_TAX_RATE_PREFIX + region.toUpperCase(), DEFAULT_TAX_RATE);
    }

    public void setTaxRate(String region, BigDecimal rate) {
        if (region != null && !region.isEmpty() && rate != null) {
            saveSetting(KEY_TAX_RATE_PREFIX + region.toUpperCase(), rate.toString());
        }
    }

    // ── Hourly Rate ───────────────────────────────────────────────────────────

    public BigDecimal getCurrentHourlyRate(String currency) {
        if ("INR".equalsIgnoreCase(currency)) {
            return getBigDecimalSetting(KEY_HOURLY_RATE_INR, DEFAULT_HOURLY_RATE_INR);
        }
        return getBigDecimalSetting(KEY_HOURLY_RATE_USD, DEFAULT_HOURLY_RATE_USD);
    }

    public BigDecimal getQualityMultiplier(int streamQuality) {
        // Original (0) = 1.0x
        // 720p (720) = 2.0x
        // 1080p (1080) = 3.0x
        if (streamQuality == 1080) {
            return new BigDecimal("3.0");
        } else if (streamQuality == 720) {
            return new BigDecimal("2.0");
        }
        return BigDecimal.ONE;
    }

    public void setCurrentHourlyRate(String currency, BigDecimal rate) {
        if (rate.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Hourly rate cannot be negative");
        }
        log.info("Platform hourly rate updated to {}/hr for currency {}.", rate, currency);
        if ("INR".equalsIgnoreCase(currency)) {
            saveSetting(KEY_HOURLY_RATE_INR, rate.toString());
        } else {
            saveSetting(KEY_HOURLY_RATE_USD, rate.toString());
        }
    }

    public BigDecimal calculateFinalRate(String currency, int quality, int destCount, int planDiscountPercent, boolean poweredBy) {
        BigDecimal baseRate = getCurrentHourlyRate(currency);
        BigDecimal qualityMultiplier = getQualityMultiplier(quality);
        
        BigDecimal rate = baseRate.multiply(qualityMultiplier).multiply(new BigDecimal(Math.max(1, destCount)));
        
        int discountPercent = Math.max(planDiscountPercent, poweredBy ? 10 : 0);
        if (discountPercent > 0) {
            BigDecimal discountMultiplier = new BigDecimal(100 - discountPercent).divide(new BigDecimal("100"));
            rate = rate.multiply(discountMultiplier);
        }
        
        return rate.setScale(4, java.math.RoundingMode.HALF_UP);
    }

    // ── Daily Budget Cap ──────────────────────────────────────────────────────

    public BigDecimal getDailyBudgetCap(String currency) {
        if ("INR".equalsIgnoreCase(currency)) {
            return getBigDecimalSetting(KEY_DAILY_BUDGET_CAP_INR, DEFAULT_DAILY_CAP_INR);
        }
        return getBigDecimalSetting(KEY_DAILY_BUDGET_CAP_USD, DEFAULT_DAILY_CAP_USD);
    }

    public void setDailyBudgetCap(String currency, BigDecimal cap) {
        if ("INR".equalsIgnoreCase(currency)) {
            saveSetting(KEY_DAILY_BUDGET_CAP_INR, cap.toString());
        } else {
            saveSetting(KEY_DAILY_BUDGET_CAP_USD, cap.toString());
        }
    }

    // ── Daily Spend Tracker (DB-backed, survives restarts) ────────────────────

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BigDecimal getDailySpend(String currency) {
        String storedDate = getStringSetting(KEY_DAILY_SPEND_DATE, "");
        String today = LocalDate.now(ZoneId.of("UTC")).toString();

        if (!today.equals(storedDate)) {
            log.info("New UTC day detected. Resetting daily spend tracker.");
            saveSetting(KEY_DAILY_SPEND_DATE, today);
            // Reset both
            saveSetting(KEY_DAILY_SPEND_AMOUNT + "USD", "0.0");
            saveSetting(KEY_DAILY_SPEND_AMOUNT + "INR", "0.0");
            return BigDecimal.ZERO;
        }

        String key = KEY_DAILY_SPEND_AMOUNT + currency.toUpperCase();
        return getBigDecimalSetting(key, BigDecimal.ZERO);
    }

    /**
     * Atomically add an incremental cost to today's platform spend.
     * Uses REQUIRES_NEW to ensure it commits immediately and doesn't hold locks for the whole watchdog tick.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void addToDailySpend(String currency, BigDecimal incrementalCost) {
        BigDecimal current = getDailySpend(currency);
        String key = KEY_DAILY_SPEND_AMOUNT + currency.toUpperCase();
        saveSetting(key, current.add(incrementalCost).toString());
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private String getStringSetting(String key, String defaultValue) {
        try {
            return appSettingRepository.findById(key)
                    .map(AppSetting::getSettingValue)
                    .orElse(defaultValue);
        } catch (Exception e) {
            log.warn("Failed to read app setting '{}', using default '{}'", key, defaultValue, e);
            return defaultValue;
        }
    }

    private int getIntSetting(String key, int defaultValue) {
        try {
            return Integer.parseInt(getStringSetting(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private BigDecimal getBigDecimalSetting(String key, BigDecimal defaultValue) {
        try {
            String val = getStringSetting(key, defaultValue.toString());
            return new BigDecimal(val);
        } catch (Exception e) {
            log.warn("Invalid BigDecimal value for app setting '{}', using default {}", key, defaultValue);
            return defaultValue;
        }
    }

    private void saveSetting(String key, String value) {
        appSettingRepository.save(new AppSetting(key, value));
    }
}
