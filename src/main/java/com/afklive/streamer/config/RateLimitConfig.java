package com.afklive.streamer.config;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.Bandwidth;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;



@Configuration
public class RateLimitConfig {
    
    @Value("${rate-limiter.capacity:10}")
    private long capacity;
    
    @Value("${rate-limiter.refill-tokens:1}")
    private long refillTokens;
    
    @Bean(name = "streamRateLimiter")
    public Bucket bucket() {
        return Bucket.builder()
            .addLimit(Bandwidth.simple(capacity, Duration.ofDays(365)))
            .build();
    }
}