package com.afklive.streamer.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;


@Configuration
public class RateLimitConfig {

    @Value("${rate-limiter.capacity:5}")
    private long capacity;

    @Bean(name = "streamRateLimiter")
    public Bucket bucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.simple(capacity, Duration.ofDays(365)))
                .build();
    }
}