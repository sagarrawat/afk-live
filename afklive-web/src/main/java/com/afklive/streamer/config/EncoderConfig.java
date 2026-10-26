package com.afklive.streamer.config;

import com.afklive.streamer.service.EncoderSettings;
import com.afklive.streamer.service.VideoBitrates;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The live encoder's x264 settings, from {@code app.streaming.x264-preset} (env APP_STREAMING_X264PRESET)
 * and {@code app.streaming.x264-threads} (env APP_STREAMING_X264THREADS), and the live video bitrate per plan and
 * height class (FO-4, {@code app.streaming.bitrate.<plan>.<class>}). See docs/operations.md "Live encoder".
 */
@Configuration
@Slf4j
public class EncoderConfig {

    @Bean
    public EncoderSettings encoderSettings(@Value("${app.streaming.x264-preset:" + EncoderSettings.DEFAULT_PRESET + "}") String preset,
                                           @Value("${app.streaming.x264-threads:0}") String threads) {
        EncoderSettings s = EncoderSettings.fromConfig(preset, threads);
        log.info("Live encoder: x264 preset {}, threads {}", s.preset(), s.threads() == 0 ? "auto" : s.threads());
        return s;
    }

    /** FO-4: read on web instances (which record each run's bitrate at go-live) and workers alike. */
    @Bean
    public VideoBitrates videoBitrates(Environment env) {
        VideoBitrates b = VideoBitrates.fromConfig(env::getProperty);
        log.info("Live video bitrates (kbps at 30 fps{}): {}", b.isBuiltIn() ? ", built-in" : "", b.describe());
        return b;
    }
}
