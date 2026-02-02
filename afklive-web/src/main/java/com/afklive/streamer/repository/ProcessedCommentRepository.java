package com.afklive.streamer.repository;

import com.afklive.streamer.model.ProcessedComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Set;

public interface ProcessedCommentRepository extends JpaRepository<ProcessedComment, Long> {
    boolean existsByCommentId(String commentId);

    @Query("SELECT p.commentId FROM ProcessedComment p WHERE p.commentId IN :ids")
    Set<String> findExistingCommentIds(@Param("ids") Collection<String> ids);
}
