package com.afklive.streamer.service;

import com.afklive.streamer.model.PlanConfig;
import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.model.Subscription;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.PlanConfigRepository;
import com.afklive.streamer.repository.SubscriptionRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PlanService {

    private final PlanConfigRepository planConfigRepository;
    private final SubscriptionRepository subscriptions;

    /** This bean through its proxy, so {@link #limitsFor} reads the plan through the cache. */
    @Autowired
    @Lazy
    private PlanService self;

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

    /**
     * The plan as it is sold today (prices, and the limits a new subscriber gets). For what a given account may do,
     * use {@link #limitsFor}: a paying subscriber keeps the limits they bought (BI-10).
     */
    @Cacheable("planConfig")
    public PlanConfig getPlanConfig(PlanType planType) {
        return planConfigRepository.findById(planType)
                .orElseThrow(() -> new IllegalArgumentException("Plan config not found for type: " + planType));
    }

    /**
     * BI-10: the limits that apply to this account: its plan's current limits, made more generous by the terms
     * recorded on its subscription when it bought the plan (grandfathering). Never less than the plan's current
     * limits, and never less than what the subscriber paid for. The result is a copy only when terms apply.
     */
    public PlanConfig limitsFor(User u) {
        PlanService plans = self == null ? this : self;
        PlanConfig current = plans.getPlanConfig(u.getPlanType());
        if (u.getId() == null || u.getPlanType() == PlanType.FREE) {
            return current;
        }
        return subscriptions.findByUserId(u.getId())
                .filter(s -> s.getStatus() != Subscription.Status.CANCELED)
                .filter(s -> s.getTerms() != null && s.getTerms().getPlan() == u.getPlanType())
                .map(s -> s.getTerms().merge(current))
                .orElse(current);
    }

    @Transactional
    @CacheEvict(value = {"plans", "planConfig"}, allEntries = true)
    public PlanConfig updatePlan(PlanConfig planConfig) {
        return planConfigRepository.save(planConfig);
    }
}
