package com.afklive.streamer.repository;

import com.afklive.streamer.model.AdminAction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdminActionRepository extends JpaRepository<AdminAction, Long> {
    List<AdminAction> findTop50ByTargetTypeAndTargetIdOrderByCreatedAtDesc(String targetType, String targetId);

    Page<AdminAction> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
