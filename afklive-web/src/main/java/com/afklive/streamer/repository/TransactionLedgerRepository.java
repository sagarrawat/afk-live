package com.afklive.streamer.repository;

import com.afklive.streamer.model.TransactionLedger;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TransactionLedgerRepository extends JpaRepository<TransactionLedger, Long> {

    @Query("SELECT t FROM TransactionLedger t WHERE t.user.email = :email ORDER BY t.createdAt DESC")
    Page<TransactionLedger> findByUserEmailOrderByCreatedAtDesc(String email, Pageable pageable);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM TransactionLedger t WHERE t.transactionType = 'DEPOSIT'")
    java.math.BigDecimal getTotalRevenue();
}
