package com.afklive.streamer.service;

import com.afklive.streamer.model.PlanConfig;
import com.afklive.streamer.model.PlanType;
import com.afklive.streamer.repository.PlanConfigRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
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
                PlanConfig config = new PlanConfig(
                        type,
                        type.getDisplayName(),
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

    public List<PlanConfig> getAllPlans() {
        return planConfigRepository.findAll();
    }

    public PlanConfig getPlanConfig(PlanType planType) {
        return planConfigRepository.findById(planType)
                .orElseThrow(() -> new IllegalArgumentException("Plan config not found for type: " + planType));
    }

    @Transactional
    public PlanConfig updatePlan(PlanConfig planConfig) {
        return planConfigRepository.save(planConfig);
    }
}
