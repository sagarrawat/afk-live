package com.afklive.streamer.service;

import com.afklive.streamer.model.QuotaAudit;
import com.afklive.streamer.repository.QuotaAuditRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class QuotaTrackingService {

    private final QuotaAuditRepository quotaAuditRepository;

    @Transactional
    public void logApiCall(String username, String apiName, int cost, String status) {
        QuotaAudit audit = new QuotaAudit(apiName, cost, username, status);
        quotaAuditRepository.save(audit);
    }

    public int getDailyUsage() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        return quotaAuditRepository.sumCostSince(startOfDay);
    }

    public Map<String, Long> getDailyBreakdown() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        List<Object[]> results = quotaAuditRepository.getUsageBreakdown(startOfDay);
        Map<String, Long> breakdown = new HashMap<>();
        for (Object[] result : results) {
            String apiName = (String) result[0];
            Number cost = (Number) result[1];
            breakdown.put(apiName, cost.longValue());
        }
        return breakdown;
    }
}
