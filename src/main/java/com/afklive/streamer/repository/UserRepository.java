package com.afklive.streamer.repository;

import com.afklive.streamer.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, String> {
}
