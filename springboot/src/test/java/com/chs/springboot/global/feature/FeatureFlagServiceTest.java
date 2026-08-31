package com.chs.springboot.global.feature;

import com.chs.springboot.global.config.service.AppConfigService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FeatureFlagServiceTest {

    private final AppConfigService appConfigService = mock(AppConfigService.class);
    private final FeatureFlagService service = new FeatureFlagService(appConfigService);

    @Test
    void parsesEnabledValuesFromAppConfig() {
        when(appConfigService.get(FeatureFlagService.KEY_TRADE_THRESHOLD_EDIT)).thenReturn(" 1 ");

        assertThat(service.isTradeThresholdEditEnabled()).isTrue();
    }

    @Test
    void fallsBackToDefaultWhenAppConfigFails() {
        when(appConfigService.get(FeatureFlagService.KEY_TRADE_THRESHOLD_EDIT))
                .thenThrow(new IllegalStateException("db down"));
        when(appConfigService.get(FeatureFlagService.KEY_MONITOR_ALLOWED_IP_MANAGE))
                .thenThrow(new IllegalStateException("db down"));

        assertThat(service.getAll())
                .containsEntry("tradeThresholdEdit", true)
                .containsEntry("monitorAllowedIpManage", false);
    }

    @Test
    void writesFlagsThroughAppConfigService() {
        service.setTradeThresholdEdit(false);
        service.setMonitorAllowedIpManage(true);

        verify(appConfigService).set(FeatureFlagService.KEY_TRADE_THRESHOLD_EDIT, "OFF");
        verify(appConfigService).set(FeatureFlagService.KEY_MONITOR_ALLOWED_IP_MANAGE, "ON");
    }
}
