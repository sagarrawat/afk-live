package com.afklive.streamer.service;

import com.afklive.streamer.model.WorkerStatus;
import com.afklive.streamer.service.CapacityEstimator.Estimate;
import com.afklive.streamer.service.CapacityEstimator.Quality;
import com.afklive.streamer.service.CapacityEstimator.Room;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** GL-7: the CPU estimate per output quality and whether it fits what the workers report as free. */
class CapacityEstimatorTest {

    private static final List<Quality> AGENCY = List.of(new Quality(720, 30), new Quality(720, 60), new Quality(1080, 30),
            new Quality(1080, 60), new Quality(1440, 30), new Quality(1440, 60), new Quality(2160, 30), new Quality(2160, 60));

    private static WorkerStatus worker(Integer cpus, int streams, int capacity, Double cpuPercent) {
        WorkerStatus w = new WorkerStatus();
        w.setWorkerId("w" + Math.random());
        w.setCpus(cpus);
        w.setStreams(streams);
        w.setCapacity(capacity);
        w.setCpuPercent(cpuPercent);
        return w;
    }

    @Test
    void theTableIsCalibratedOnOurWorkers() {
        // 720p30 ≈ 0.5 encode, 1080p30 ≈ 1.0, 1080p60 ≈ 2.0, plus a little decoding; copy mode almost nothing.
        assertThat(CapacityEstimator.streamCpus(720, 30, false)).isEqualTo(0.6);
        assertThat(CapacityEstimator.streamCpus(1080, 30, false)).isEqualTo(1.1);
        assertThat(CapacityEstimator.streamCpus(1080, 60, false)).isEqualTo(2.1);
        assertThat(CapacityEstimator.streamCpus(2160, 30, false)).isEqualTo(4.1);
        assertThat(CapacityEstimator.streamCpus(1080, 60, true)).isEqualTo(0.01);
    }

    @Test
    void freeRoomIsTheBestWorkerWithASlotLeavingHeadroom() {
        // The 24 Oct 2026 worker: 4 CPUs at 92 % has nothing usable left.
        assertThat(CapacityEstimator.room(List.of(worker(4, 3, 4, 92.0)))).isEqualTo(new Room(0.0, 3.4));
        // Half busy: 85 % of 4 CPUs usable, 2 in use.
        assertThat(CapacityEstimator.room(List.of(worker(4, 1, 4, 50.0))).best()).isEqualTo(1.4);
        // A full worker has no room however idle it is; the best of several counts.
        assertThat(CapacityEstimator.room(List.of(worker(8, 4, 4, 10.0), worker(4, 0, 4, 25.0))).best()).isEqualTo(2.4);
        // A worker on the previous release doesn't send its CPU count: one per stream slot.
        assertThat(CapacityEstimator.room(List.of(worker(null, 0, 4, 0.0)))).isEqualTo(new Room(3.4, 3.4));
        // Nobody reported load: unknown.
        assertThat(CapacityEstimator.room(List.of(worker(4, 0, 4, null)))).isNull();
        assertThat(CapacityEstimator.room(List.of())).isNull();
        // OPS-3: a worker being replaced takes on nothing new, so its room doesn't count.
        WorkerStatus draining = worker(8, 0, 4, 5.0);
        draining.setDrainingSince(java.time.ZonedDateTime.now());
        assertThat(CapacityEstimator.room(List.of(draining, worker(4, 1, 4, 50.0))).best()).isEqualTo(1.4);
        assertThat(CapacityEstimator.room(List.of(draining))).isNull();
    }

    @Test
    void theIncidentQualityOnABusyWorkerWarnsAndOffersTheBestThatFits() {
        // 1080p60 needs ~2.1; 1.2 free: 1080p30 (1.1) fits and is sharper than 720p 60 at the same cost.
        Estimate e = CapacityEstimator.estimate(new Quality(1080, 60), false, AGENCY, new Room(1.2, 3.4));
        assertThat(e.fits()).isFalse();
        assertThat(e.impossible()).isFalse();
        assertThat(e.suggestion()).isEqualTo(new Quality(1080, 30));
        assertThat(e.suggestionFits()).isTrue();
        assertThat(e.message()).contains("estimate", "slower than real time", "1080p 30 fps fits");

        // Only 0.7 free: 720p 30 fps.
        Estimate tight = CapacityEstimator.estimate(new Quality(1080, 60), false, AGENCY, new Room(0.7, 3.4));
        assertThat(tight.suggestion()).isEqualTo(new Quality(720, 30));
        assertThat(tight.suggestionFits()).isTrue();

        // Nothing free: still the lightest, honestly labelled.
        Estimate full = CapacityEstimator.estimate(new Quality(1080, 60), false, AGENCY, new Room(0.0, 3.4));
        assertThat(full.suggestion()).isEqualTo(new Quality(720, 30));
        assertThat(full.suggestionFits()).isFalse();
        assertThat(full.message()).contains("may struggle");
    }

    @Test
    void itFitsWhenThereIsRoomOrWhenItPlaysByCopyAndSaysNothingWhenUnknown() {
        assertThat(CapacityEstimator.estimate(new Quality(1080, 60), false, AGENCY, new Room(3.0, 3.4)).fits()).isTrue();
        assertThat(CapacityEstimator.estimate(new Quality(1080, 60), true, AGENCY, new Room(0.1, 3.4)).fits()).isTrue();
        Estimate unknown = CapacityEstimator.estimate(new Quality(2160, 60), false, AGENCY, null);
        assertThat(unknown.fits()).isTrue();
        assertThat(unknown.freeCpus()).isNull();
        assertThat(unknown.message()).isNull();
    }

    @Test
    void onlyAQualityNoServerCouldRunIsImpossible() {
        // 4K30 ≈ 4.1 CPUs: more than a 4-CPU worker can give even when idle.
        Estimate e = CapacityEstimator.estimate(new Quality(2160, 30), false, AGENCY, new Room(3.4, 3.4));
        assertThat(e.impossible()).isTrue();
        assertThat(e.fits()).isFalse();
        assertThat(e.suggestion()).isEqualTo(new Quality(1440, 30)); // 1.9: the sharpest that fits
        assertThat(e.message()).contains("Pick a lower quality, such as 1440p 30 fps");
        // On an 8-CPU worker it's merely busy-or-not.
        assertThat(CapacityEstimator.estimate(new Quality(2160, 30), false, AGENCY, new Room(1.0, 6.8)).impossible()).isFalse();
    }

    @Test
    void nothingLighterInThePlanMeansNoSuggestion() {
        Estimate e = CapacityEstimator.estimate(new Quality(720, 30), false, List.of(new Quality(720, 30)), new Room(0.2, 3.4));
        assertThat(e.fits()).isFalse();
        assertThat(e.suggestion()).isNull();
    }
}
