package com.afklive.streamer.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ApplicationAvailabilityBean;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.support.GenericApplicationContext;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** OPS-4: a stopping web slot reports "not ready" first, then keeps serving for the drain time. */
class ShutdownDrainTest {

    private final AtomicReference<ReadinessState> stateDuringPause = new AtomicReference<>();
    private final AtomicReference<Duration> paused = new AtomicReference<>();

    private GenericApplicationContext context(Duration drain, String role) {
        GenericApplicationContext ctx = new GenericApplicationContext();
        ctx.registerBean(ApplicationAvailabilityBean.class);
        ctx.registerBean(ShutdownDrain.class, () -> new ShutdownDrain(ctx, drain, role) {
            @Override
            protected void pause(Duration d) {
                stateDuringPause.set(ctx.getBean(ApplicationAvailability.class).getReadinessState());
                paused.set(d);
            }
        });
        ctx.refresh();
        AvailabilityChangeEvent.publish(ctx, ReadinessState.ACCEPTING_TRAFFIC);
        return ctx;
    }

    @Test
    void reportsNotReadyThenWaitsTheDrainTime() {
        context(Duration.ofSeconds(5), "web").close();
        assertThat(stateDuringPause.get()).isEqualTo(ReadinessState.REFUSING_TRAFFIC);
        assertThat(paused.get()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void noDrainByDefault() {
        context(Duration.ZERO, "web").close();
        assertThat(paused.get()).isNull();
    }

    @Test
    void workersNeverWait() {
        // Nothing is proxied to a worker; its stop must stay fast (TimeoutStopSec covers closing streams only).
        context(Duration.ofSeconds(5), "worker").close();
        assertThat(paused.get()).isNull();
    }
}
