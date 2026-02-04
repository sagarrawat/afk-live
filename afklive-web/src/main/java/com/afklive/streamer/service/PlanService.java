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

    @PostConstruct
    public void initPlans() {
        if (planConfigRepository.count() == 0) {
            for (PlanType type : PlanType.values()) {
                String defaultPrice = switch (type) {
                    case FREE -> "₹0";
                    case ESSENTIALS -> "₹199";
                    case TEAM -> "₹999";
                    default -> "₹0";
                };

                String defaultCycle = switch (type) {
                    case FREE -> "Hourly";
                    case ESSENTIALS -> "Monthly";
                    case TEAM -> "Monthly";
                    default -> "Monthly";
                };

                PlanConfig config = new PlanConfig(
                        type,
                        type.getDisplayName(),
                        defaultPrice,
                        defaultCycle,
                        type.getMaxStorageBytes(),
                        type.getMaxScheduledPosts(),
                        type.getMaxActiveStreams(),
                        type.getMaxChannels(),
                        type.getMaxResolution()
                );
                planConfigRepository.save(config);
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
