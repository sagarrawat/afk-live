package com.afklive.streamer.repository;

import com.afklive.streamer.model.StreamDestination;
import com.afklive.streamer.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface StreamDestinationRepository extends JpaRepository<StreamDestination, Long> {
    List<StreamDestination> findByUser(User user);
    List<StreamDestination> findByUserAndSelectedTrue(User user);
    List<StreamDestination> findByStreamKeyAndUser(String streamKey, User user);
}
