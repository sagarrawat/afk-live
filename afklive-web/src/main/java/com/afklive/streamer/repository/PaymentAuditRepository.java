package com.afklive.streamer.repository;

import com.afklive.streamer.model.PaymentAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PaymentAuditRepository extends JpaRepository<PaymentAudit, Long> {
    Optional<PaymentAudit> findByTransactionId(String transactionId);
}
