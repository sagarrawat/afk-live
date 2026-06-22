package com.afklive.streamer.service;

import com.afklive.streamer.model.AppSetting;
import com.afklive.streamer.repository.AppSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AppConfigServiceTest {

    @Mock
    private AppSettingRepository appSettingRepository;

    @InjectMocks
    private AppConfigService appConfigService;

    @Test
    void testGetTaxRateDefault() {
        when(appSettingRepository.findById("tax_rate_IN")).thenReturn(Optional.empty());
        BigDecimal taxRate = appConfigService.getTaxRate("IN");
        assertEquals(BigDecimal.ZERO, taxRate);
    }

    @Test
    void testGetTaxRateConfigured() {
        AppSetting setting = new AppSetting("tax_rate_US", "7.50");
        when(appSettingRepository.findById("tax_rate_US")).thenReturn(Optional.of(setting));
        BigDecimal taxRate = appConfigService.getTaxRate("US");
        assertEquals(new BigDecimal("7.50"), taxRate);
    }
}
