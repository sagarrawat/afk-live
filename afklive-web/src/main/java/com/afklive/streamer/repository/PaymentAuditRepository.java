package com.afklive.streamer.repository;

import com.afklive.streamer.model.PaymentAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PaymentAuditRepository extends JpaRepository<PaymentAudit, Long> {
    Optional<PaymentAudit> findByTransactionId(String transactionId);

    /** Row-locked read so a payment is applied exactly once even if confirmations race. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PaymentAudit p WHERE p.transactionId = :transactionId")
    Optional<PaymentAudit> findForUpdateByTransactionId(@Param("transactionId") String transactionId);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM PaymentAudit p WHERE p.status = :status")
    Long sumAmountByStatus(String status);

    java.util.List<PaymentAudit> findTop50ByMerchantUserIdOrderByCreatedAtDesc(String merchantUserId);
}
