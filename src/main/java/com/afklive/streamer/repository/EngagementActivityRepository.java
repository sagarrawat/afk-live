package com.afklive.streamer.repository;

import com.afklive.streamer.model.EngagementActivity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface EngagementActivityRepository extends JpaRepository<EngagementActivity, Long> {
    List<EngagementActivity> findByUsernameOrderByTimestampDesc(String username);
}
