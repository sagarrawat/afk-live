package com.afklive.streamer.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "plan_configs")
public class PlanConfig {
    @Id
    @Enumerated(EnumType.STRING)
    private PlanType planType;

    private String displayName;
    private String price;
    private long maxStorageBytes;
    private int maxScheduledPosts;
    private int maxActiveStreams;
    private int maxChannels;
    private int maxResolution;
}
