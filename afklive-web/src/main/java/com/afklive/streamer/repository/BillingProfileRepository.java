package com.afklive.streamer.repository;

import com.afklive.streamer.model.BillingProfile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BillingProfileRepository extends JpaRepository<BillingProfile, Long> {
}
