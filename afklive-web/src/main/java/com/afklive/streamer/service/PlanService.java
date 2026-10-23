package com.afklive.streamer.service;

import com.afklive.streamer.model.PlanConfig;
import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.repository.PlanConfigRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PlanService {

    private final PlanConfigRepository planConfigRepository;

    /**
     * Makes sure every plan has a row, and fills new columns of rows created by an older version.
     * Admin edits to a row that's already current are never overwritten.
     */
    @PostConstruct
    public void initPlans() {
        for (PlanType type : PlanType.values()) {
            PlanConfig c = planConfigRepository.findById(type).orElse(null);
            if (c == null || c.getDefaultsVersion() < PlanConfig.CURRENT_DEFAULTS) {
                PlanConfig fresh = c == null ? new PlanConfig() : c;
                fresh.applyDefaults(type);
                planConfigRepository.save(fresh);
            }
        }
    }

    @Cacheable("plans")
    public List<PlanConfig> getAllPlans() {
        return planConfigRepository.findAll();
    }

    @Cacheable("planConfig")
    public PlanConfig getPlanConfig(PlanType planType) {
        return planConfigRepository.findById(planType)
                .orElseThrow(() -> new IllegalArgumentException("Plan config not found for type: " + planType));
    }

    @Transactional
    @CacheEvict(value = {"plans", "planConfig"}, allEntries = true)
    public PlanConfig updatePlan(PlanConfig planConfig) {
        return planConfigRepository.save(planConfig);
    }
}
