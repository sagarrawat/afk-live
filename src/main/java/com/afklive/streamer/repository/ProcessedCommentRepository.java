package com.afklive.streamer.repository;

import com.afklive.streamer.model.ProcessedComment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedCommentRepository extends JpaRepository<ProcessedComment, Long> {
    boolean existsByCommentId(String commentId);
}
