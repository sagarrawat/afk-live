package com.afklive.streamer.repository;

import com.afklive.streamer.model.ETagStore;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ETagStoreRepository extends JpaRepository<ETagStore, String> {
}
