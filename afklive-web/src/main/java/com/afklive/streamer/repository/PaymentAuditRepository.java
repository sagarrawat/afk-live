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

    java.util.List<PaymentAudit> findTop50ByMerchantUserIdOrderByCreatedAtDesc(String merchantUserId);

    /** BI-9: has this account ever paid for a plan (refunded payments count; paying off a usage balance doesn't)? */
    @Query("SELECT COUNT(p) > 0 FROM PaymentAudit p WHERE p.merchantUserId = :email"
            + " AND p.status IN ('COMPLETED', 'SUCCESS', 'REFUNDED', 'PARTIALLY_REFUNDED') AND (p.kind IS NULL OR p.kind <> 'BALANCE')")
    boolean hasPaidForPlan(@Param("email") String email);

    /** Completed payments since a time, per currency: rows of [currency (null = INR), sum of amounts]. */
    @Query("SELECT p.currency, COALESCE(SUM(p.amount), 0) FROM PaymentAudit p WHERE p.status IN ('COMPLETED', 'SUCCESS')"
            + " AND p.createdAt >= :since GROUP BY p.currency")
    java.util.List<Object[]> sumCompletedByCurrencySince(@Param("since") java.time.LocalDateTime since);
}
