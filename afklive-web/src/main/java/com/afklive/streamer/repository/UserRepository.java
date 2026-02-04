package com.afklive.streamer.repository;

import com.afklive.streamer.model.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {
    Optional<User> findByVerificationToken(String token);
    Optional<User> findByResetToken(String token);

    Slice<User> findByAutoReplyEnabledTrue(Pageable pageable);

    @Modifying
    @Query("UPDATE User u SET u.unpaidBalance = u.unpaidBalance + :amount WHERE u.username = :username")
    void addUnpaidBalance(@Param("username") String username, @Param("amount") double amount);

    @Modifying
    @Query("UPDATE User u SET u.unpaidBalance = u.unpaidBalance - :amount WHERE u.username = :username")
    void deductUnpaidBalance(@Param("username") String username, @Param("amount") double amount);
}
