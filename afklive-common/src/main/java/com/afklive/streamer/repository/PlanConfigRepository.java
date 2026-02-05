package com.afklive.streamer.repository;

import com.afklive.streamer.model.PlanConfig;
import com.afklive.streamer.model.PlanType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PlanConfigRepository extends JpaRepository<PlanConfig, PlanType> {
}
