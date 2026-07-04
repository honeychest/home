package com.chs.springboot.global.monitor.service;

import com.chs.springboot.global.monitor.dto.MetricSnapshot;
import com.chs.springboot.global.monitor.entity.AlertHistory;
import com.chs.springboot.global.monitor.feed.FeedHealthRegistry;
import com.chs.springboot.global.monitor.health.HealthStatus;
import com.chs.springboot.global.monitor.repository.AlertHistoryRepository;
import com.chs.springboot.global.telegram.TelegramProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AlertServiceTest {

    @Test
    void staleFeedStatusIsStoredToAlertHistoryWithoutTelegram() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        TelegramProvider telegramProvider = mock(TelegramProvider.class);
        AlertHistoryRepository alertHistoryRepository = mock(AlertHistoryRepository.class);
        AlertService alertService = new AlertService(redisTemplate, telegramProvider, alertHistoryRepository);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("monitor:silence")).thenReturn(null);
        when(valueOperations.get("monitor:alert:cooldown:FEED:binance-ticker:DEGRADED")).thenReturn(null);

        MetricSnapshot snapshot = new MetricSnapshot(
                null, null, null, null, null, null, null, null, null, null,
                List.of(), null, null, null, null, null, null,
                List.of(new FeedHealthRegistry.FeedHealth("binance-ticker", HealthStatus.DEGRADED, 12L, 1_717_171_717_000L, 42L)),
                List.of(),
                LocalDateTime.of(2026, 5, 30, 12, 0, 0),
                "monitor-1"
        );

        alertService.evaluate(snapshot);

        ArgumentCaptor<AlertHistory> captor = ArgumentCaptor.forClass(AlertHistory.class);
        verify(alertHistoryRepository).save(captor.capture());
        AlertHistory saved = captor.getValue();
        assertThat(saved.getMetricType()).isEqualTo(AlertHistory.MetricType.FEED_BINANCE_TICKER);
        assertThat(saved.getSeverity()).isEqualTo(AlertHistory.Severity.WARN);
        assertThat(saved.getValue()).isEqualTo(12d);
        assertThat(saved.getThreshold()).isEqualTo(10d);
        assertThat(saved.getDurationSec()).isEqualTo(12);
        assertThat(saved.getSourceEnv()).isEqualTo("local");
        assertThat(saved.getMemo()).contains("binance-ticker").contains("DEGRADED");

        verify(valueOperations).set("monitor:alert:cooldown:FEED:binance-ticker:DEGRADED", "1", 3600, TimeUnit.SECONDS);
        verifyNoInteractions(telegramProvider);
    }

    @Test
    void sameFeedStatusIsNotStoredAgainWithinOneHourCooldown() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        TelegramProvider telegramProvider = mock(TelegramProvider.class);
        AlertHistoryRepository alertHistoryRepository = mock(AlertHistoryRepository.class);
        AlertService alertService = new AlertService(redisTemplate, telegramProvider, alertHistoryRepository);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("monitor:silence")).thenReturn(null);
        when(valueOperations.get("monitor:alert:cooldown:FEED:binance-ticker:DEGRADED")).thenReturn("1");

        MetricSnapshot snapshot = new MetricSnapshot(
                null, null, null, null, null, null, null, null, null, null,
                List.of(), null, null, null, null, null, null,
                List.of(new FeedHealthRegistry.FeedHealth("binance-ticker", HealthStatus.DEGRADED, 12L, 1_717_171_717_000L, 42L)),
                List.of(),
                LocalDateTime.of(2026, 5, 30, 12, 0, 0),
                "monitor-1"
        );

        alertService.evaluate(snapshot);

        verify(alertHistoryRepository, never()).save(any());
        verify(valueOperations, never()).set(eq("monitor:alert:cooldown:FEED:binance-ticker:DEGRADED"), any(), anyLong(), any());
        verifyNoInteractions(telegramProvider);
    }
}
