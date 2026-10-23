package com.afklive.streamer.repository;

import com.afklive.streamer.model.StreamDestination;
import com.afklive.streamer.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface StreamDestinationRepository extends JpaRepository<StreamDestination, Long> {
    List<StreamDestination> findByUser(User user);
    List<StreamDestination> findByUserAndSelectedTrue(User user);
    /** Keys are encrypted with a random IV, so they can't be compared in SQL: compare the user's few destinations here. */
    default List<StreamDestination> findByStreamKeyAndUser(String streamKey, User user) {
        if (streamKey == null) {
            return List.of();
        }
        return findByUser(user).stream().filter(d -> streamKey.equals(d.getStreamKey())).toList();
    }

    java.util.Optional<StreamDestination> findByIdAndUserEmail(Long id, String email);
}
