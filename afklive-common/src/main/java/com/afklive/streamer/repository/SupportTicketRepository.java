package com.afklive.streamer.repository;

import com.afklive.streamer.model.SupportTicket;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SupportTicketRepository extends JpaRepository<SupportTicket, Long> {
    List<SupportTicket> findAllByOrderByCreatedAtDesc();
    Page<SupportTicket> findAllByOrderByCreatedAtDesc(Pageable pageable);
    List<SupportTicket> findByUserEmailOrderByCreatedAtDesc(String userEmail);
    long countByStatus(String status);
}
