package com.afklive.streamer.config;

import com.afklive.streamer.service.EncoderSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The live encoder's x264 settings, from {@code app.streaming.x264-preset} (env APP_STREAMING_X264PRESET)
 * and {@code app.streaming.x264-threads} (env APP_STREAMING_X264THREADS). See docs/operations.md.
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
}
