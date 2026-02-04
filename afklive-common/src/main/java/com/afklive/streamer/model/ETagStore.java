package com.afklive.streamer.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "etag_store")
public class ETagStore {
    @Id
    private String id;
    private String etag;
    private LocalDateTime lastUpdated;
}
