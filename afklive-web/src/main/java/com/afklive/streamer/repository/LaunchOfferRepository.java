package com.afklive.streamer.repository;

import com.afklive.streamer.model.LaunchOffer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

public interface LaunchOfferRepository extends JpaRepository<LaunchOffer, Long> {

    /** Automatic offers open at {@code now}, newest first. */
    @Query("SELECT o FROM LaunchOffer o WHERE o.automatic = true AND o.startsAt <= :now AND o.endsAt > :now"
            + " AND (o.endedAt IS NULL OR o.endedAt > :now) ORDER BY o.startsAt DESC, o.id DESC")
    List<LaunchOffer> findActiveAutomatic(@Param("now") ZonedDateTime now);

    /** Automatic offers whose dates overlap [from, to) and that weren't ended before {@code from}. */
    @Query("SELECT o FROM LaunchOffer o WHERE o.automatic = true AND o.startsAt < :to AND o.endsAt > :from"
            + " AND (o.endedAt IS NULL OR o.endedAt > :from)")
    List<LaunchOffer> findAutomaticOverlapping(@Param("from") ZonedDateTime from, @Param("to") ZonedDateTime to);

    Optional<LaunchOffer> findByCodeIgnoreCase(String code);

    List<LaunchOffer> findTop50ByOrderByCreatedAtDesc();
}
