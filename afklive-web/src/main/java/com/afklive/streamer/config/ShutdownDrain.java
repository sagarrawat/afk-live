package com.afklive.streamer.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * OPS-4: the first step of stopping a web slot. It reports "not ready" (readiness probe 503) and keeps
 * serving for {@code app.web.shutdown-drain}, so Caddy's health check (every second) moves new requests
 * to the other slot before this one closes its port. Then the graceful shutdown
 * ({@code server.shutdown: graceful}) lets the requests already in progress finish.
 * <p>
 * Spring publishes {@link ContextClosedEvent} before it stops the web server, so the port is still
 * open while this waits. Workers never wait: nothing is proxied to them.
 */
@Component
@Slf4j
public class ShutdownDrain implements ApplicationListener<ContextClosedEvent> {

    private final ApplicationContext context;
    private final Duration drain;

    public ShutdownDrain(ApplicationContext context,
                         @Value("${app.web.shutdown-drain:0s}") Duration drain,
                         @Value("${app.streaming.role:all}") String role) {
        this.context = context;
        this.drain = drain == null || "worker".equalsIgnoreCase(role) ? Duration.ZERO : drain;
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        if (event.getApplicationContext() != context) {
            return; // a child context closing
        }
        AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
        if (drain.isZero() || drain.isNegative()) {
            return;
        }
        log.info("Stopping: reporting not ready for {} s so new requests go to the other web slot", drain.toSeconds());
        pause(drain);
    }

    /** Overridden in tests. */
    protected void pause(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    Duration drain() {
        return drain;
    }
}
