package com.afklive.streamer.repository;

import com.afklive.streamer.model.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    List<Invoice> findAllByUserIdOrderByIssuedAtDesc(Long userId);

    Optional<Invoice> findByIdAndUserId(Long id, Long userId);

    Optional<Invoice> findByPaymentOrderId(String orderId);

    /** Next invoice number in the financial year (gap-free per year, safe under concurrency). */
    @Query(value = "INSERT INTO invoice_counters(financial_year, last_number) VALUES (:fy, 1) "
            + "ON CONFLICT (financial_year) DO UPDATE SET last_number = invoice_counters.last_number + 1 "
            + "RETURNING last_number", nativeQuery = true)
    int nextNumber(@Param("fy") String financialYear);
}
