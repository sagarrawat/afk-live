package com.afklive.streamer.repository;

import com.afklive.streamer.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {
    Optional<User> findByVerificationToken(String token);
    Optional<User> findByResetToken(String token);
}
