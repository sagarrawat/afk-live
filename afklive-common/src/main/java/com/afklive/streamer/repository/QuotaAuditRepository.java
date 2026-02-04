package com.afklive.streamer.repository;

import com.afklive.streamer.model.QuotaAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface QuotaAuditRepository extends JpaRepository<QuotaAudit, Long> {

    @Query("SELECT COALESCE(SUM(q.cost), 0) FROM QuotaAudit q WHERE q.timestamp >= :startOfDay")
    Integer sumCostSince(@Param("startOfDay") LocalDateTime startOfDay);

    // Can add more stats queries here (e.g. group by apiName)
    @Query("SELECT q.apiName, SUM(q.cost) FROM QuotaAudit q WHERE q.timestamp >= :startOfDay GROUP BY q.apiName")
    java.util.List<Object[]> getUsageBreakdown(@Param("startOfDay") LocalDateTime startOfDay);
}
