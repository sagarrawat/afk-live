package com.afklive.streamer.streaming.soak;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * OPS-6: the soak run as a JUnit test. Tagged {@code soak} and excluded from every normal build (see the
 * surefire {@code excludedGroups} in afklive-web/pom.xml), so it never costs CI time. Run it with
 * {@code afklive-scripts/soak.sh}; settings come from system properties (see
 * {@link SoakHarness.Config#fromSystemProperties()}).
 */
@Tag("soak")
class SoakTest {

    @Test
    void longStreamsStayHealthy() throws Exception {
        Process v = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assumeTrue(v.waitFor(10, TimeUnit.SECONDS) && v.exitValue() == 0, "ffmpeg not installed");
        SoakHarness.Config cfg = SoakHarness.Config.fromSystemProperties();
        SoakReport report = new SoakHarness(cfg).run();
        System.out.println(report.markdown());
        assertThat(report.failures()).as("soak checks that failed; details in " + cfg.outDir()).isEmpty();
    }
}
