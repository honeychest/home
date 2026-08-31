package com.chs.springboot.global.config.service;

import com.chs.springboot.global.config.entity.AppConfig;
import com.chs.springboot.global.config.repository.AppConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AppConfigServiceTest {

    private AppConfigRepository appConfigRepository;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private AppConfigService service;

    @BeforeEach
    void setUp() {
        appConfigRepository = mock(AppConfigRepository.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new AppConfigService(appConfigRepository, redisTemplate);
    }

    @Test
    void getFallsBackToDbOnRedisMiss() {
        when(valueOperations.get("config:test")).thenReturn(null);
        when(appConfigRepository.findByConfigKey("config:test"))
                .thenReturn(Optional.of(config("config:test", "db-value")));

        assertThat(service.get("config:test")).isEqualTo("db-value");
        verify(valueOperations, never()).set("config:test", "db-value");
    }

    @Test
    void getFallsBackToDbWhenRedisThrows() {
        when(valueOperations.get("config:test")).thenThrow(new IllegalStateException("redis down"));
        when(appConfigRepository.findByConfigKey("config:test"))
                .thenReturn(Optional.of(config("config:test", "db-value")));

        assertThat(service.get("config:test")).isEqualTo("db-value");
    }

    @Test
    void setSavesDbBeforeRefreshingRedisCache() {
        AppConfig existing = config("config:test", "old-value");
        when(appConfigRepository.findByConfigKey("config:test")).thenReturn(Optional.of(existing));

        service.set("config:test", "new-value");

        InOrder order = inOrder(appConfigRepository, redisTemplate, valueOperations);
        order.verify(appConfigRepository).save(existing);
        order.verify(redisTemplate).delete("config:test");
        order.verify(valueOperations).set("config:test", "new-value");
        assertThat(existing.getConfigValue()).isEqualTo("new-value");
    }

    @Test
    void setKeepsDbValueWhenRedisRefreshFails() {
        when(appConfigRepository.findByConfigKey("config:test")).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("redis down"))
                .when(valueOperations).set("config:test", "new-value");

        service.set("config:test", "new-value");

        ArgumentCaptor<AppConfig> configCaptor = ArgumentCaptor.forClass(AppConfig.class);
        verify(appConfigRepository).save(configCaptor.capture());
        assertThat(configCaptor.getValue().getConfigValue()).isEqualTo("new-value");

        when(valueOperations.get("config:test")).thenReturn(null);
        when(appConfigRepository.findByConfigKey("config:test"))
                .thenReturn(Optional.of(configCaptor.getValue()));
        assertThat(service.get("config:test")).isEqualTo("new-value");
    }

    @Test
    void initMigratesExistingFeatureAndThemeValuesOnlyWhenDbRowIsMissing() {
        when(appConfigRepository.findByConfigKey(anyString())).thenReturn(Optional.empty());
        Map<String, String> redisValues = Map.of(
                "feature:trade:threshold-edit", " true ",
                "feature:monitor:allowed-ip-manage", "not-a-boolean",
                "monitor:silence", " true ",
                "theme:analysis", "BLACK",
                "theme:binance", "unsupported",
                "theme:trade", "teal",
                "theme:signal", " Harbor "
        );
        when(valueOperations.get(anyString())).thenAnswer(invocation ->
                redisValues.get(invocation.getArgument(0)));

        service.init();

        ArgumentCaptor<AppConfig> configCaptor = ArgumentCaptor.forClass(AppConfig.class);
        verify(appConfigRepository, times(AppConfigService.DEFAULTS.size())).save(configCaptor.capture());
        Map<String, String> saved = configCaptor.getAllValues().stream()
                .collect(java.util.stream.Collectors.toMap(AppConfig::getConfigKey, AppConfig::getConfigValue));
        assertThat(saved).containsEntry("feature:trade:threshold-edit", "ON")
                .containsEntry("feature:monitor:allowed-ip-manage", "OFF")
                .containsEntry("monitor:silence", "ON")
                .containsEntry("theme:analysis", "black")
                .containsEntry("theme:binance", "dark")
                .containsEntry("theme:trade", "teal")
                .containsEntry("theme:signal", "harbor");
    }

    @Test
    void silenceGetFallsBackToDbOnRedisMiss() {
        when(valueOperations.get("monitor:silence")).thenReturn(null);
        when(appConfigRepository.findByConfigKey("monitor:silence"))
                .thenReturn(Optional.of(config("monitor:silence", "ON")));

        assertThat(service.get("monitor:silence")).isEqualTo("ON");
    }

    @Test
    void silenceGetFallsBackToDbWhenRedisThrows() {
        when(valueOperations.get("monitor:silence")).thenThrow(new IllegalStateException("redis down"));
        when(appConfigRepository.findByConfigKey("monitor:silence"))
                .thenReturn(Optional.of(config("monitor:silence", "OFF")));

        assertThat(service.get("monitor:silence")).isEqualTo("OFF");
    }

    @Test
    void existingDbSilenceWinsAndSkipsRedisMigration() {
        when(appConfigRepository.findByConfigKey(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return "monitor:silence".equals(key)
                    ? Optional.of(config(key, "OFF"))
                    : Optional.empty();
        });
        when(valueOperations.get(anyString())).thenReturn("ON");

        service.init();

        verify(valueOperations, never()).get("monitor:silence");
    }

    @Test
    void existingDbThemeWinsAndSkipsRedisMigration() {
        AppConfig existing = config("theme:analysis", "harbor");
        when(appConfigRepository.findByConfigKey(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return "theme:analysis".equals(key)
                    ? Optional.of(existing)
                    : Optional.empty();
        });
        when(valueOperations.get(anyString())).thenReturn("teal");

        service.init();

        verify(valueOperations, never()).get("theme:analysis");
        verify(appConfigRepository, never()).save(existing);
        assertThat(existing.getConfigValue()).isEqualTo("harbor");
    }

    private static AppConfig config(String key, String value) {
        AppConfig config = new AppConfig();
        config.setConfigKey(key);
        config.setConfigValue(value);
        return config;
    }
}
