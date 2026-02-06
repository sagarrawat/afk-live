package com.afklive.streamer.service;

import com.afklive.streamer.model.AppSetting;
import com.afklive.streamer.repository.AppSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AppConfigService {
    private final AppSettingRepository appSettingRepository;

    private static final String GLOBAL_MAX_STREAMS = "global_max_streams";
    private static final int DEFAULT_MAX_STREAMS = 100;

    public int getGlobalStreamLimit() {
        try {
            var setting = appSettingRepository.findById(GLOBAL_MAX_STREAMS);
            if (setting.isPresent()) {
                return Integer.parseInt(setting.get().getSettingValue());
            }
        } catch (Exception e) {
            log.warn("Failed to fetch global stream limit, using default {}", DEFAULT_MAX_STREAMS, e);
        }
        return DEFAULT_MAX_STREAMS;
    }

    public void setGlobalStreamLimit(int limit) {
        if (limit < 0) {
            limit = 0;
        }
        AppSetting setting = new AppSetting(GLOBAL_MAX_STREAMS, String.valueOf(limit));
        appSettingRepository.save(setting);
    }
}
