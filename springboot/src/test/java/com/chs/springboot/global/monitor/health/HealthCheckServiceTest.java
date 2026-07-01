package com.chs.springboot.global.monitor.health;

import com.chs.springboot.global.monitor.feed.FeedHealthRegistry;
import com.chs.springboot.global.monitor.service.MetricCollectorService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthCheckServiceTest {

    private final FeedHealthRegistry feedHealthRegistry = mock(FeedHealthRegistry.class);
    private final HealthHeartbeat healthHeartbeat = mock(HealthHeartbeat.class);
    private final HealthCheckEventRepository eventRepository = mock(HealthCheckEventRepository.class);
    private final MetricCollectorService metricCollectorService = mock(MetricCollectorService.class);
    private final InfraHealthProbe infraHealthProbe = mock(InfraHealthProbe.class);
    private final HealthCheckService service =
            new HealthCheckService(feedHealthRegistry, healthHeartbeat, eventRepository, metricCollectorService, infraHealthProbe);

    {
        InfraHealthProbe.Probe up = new InfraHealthProbe.Probe(HealthStatus.UP, "정상 응답");
        when(infraHealthProbe.mysql()).thenReturn(up);
        when(infraHealthProbe.redis()).thenReturn(up);
        when(infraHealthProbe.kafka()).thenReturn(up);
        when(infraHealthProbe.postgres()).thenReturn(up);
    }

    @Test
    void resourceUnderDegradedThresholdIsUp() {
        when(metricCollectorService.getLastCpu()).thenReturn(50d);

        HealthStatus status = statusOf(HealthCheckCatalog.RES_CPU.key());

        assertThat(status).isEqualTo(HealthStatus.UP);
    }

    @Test
    void resourceAtDegradedThresholdIsDegraded() {
        when(metricCollectorService.getLastRam()).thenReturn(70d);

        HealthStatus status = statusOf(HealthCheckCatalog.RES_RAM.key());

        assertThat(status).isEqualTo(HealthStatus.DEGRADED);
    }

    @Test
    void resourceAtDownThresholdIsDown() {
        when(metricCollectorService.getLastDisk()).thenReturn(80d);

        HealthStatus status = statusOf(HealthCheckCatalog.RES_DISK.key());

        assertThat(status).isEqualTo(HealthStatus.DOWN);
    }

    @Test
    void resourceNeverCollectedIsUnknown() {
        when(metricCollectorService.getLastCpu()).thenReturn(-1d);

        HealthStatus status = statusOf(HealthCheckCatalog.RES_CPU.key());

        assertThat(status).isEqualTo(HealthStatus.UNKNOWN);
    }

    @Test
    void infraProbeUpIsUp() {
        when(infraHealthProbe.mysql()).thenReturn(new InfraHealthProbe.Probe(HealthStatus.UP, "정상 응답"));

        HealthStatus status = statusOf(HealthCheckCatalog.INFRA_MYSQL.key());

        assertThat(status).isEqualTo(HealthStatus.UP);
    }

    @Test
    void infraProbeDownIsDown() {
        when(infraHealthProbe.kafka()).thenReturn(new InfraHealthProbe.Probe(HealthStatus.DOWN, "브로커 노드 0개"));

        HealthStatus status = statusOf(HealthCheckCatalog.INFRA_KAFKA.key());

        assertThat(status).isEqualTo(HealthStatus.DOWN);
    }

    private static final long GB = 1024L * 1024 * 1024;

    @Test
    void rawTableUnderDegradedThresholdIsUp() {
        when(metricCollectorService.getLastRawAggTradeBytes()).thenReturn(1 * GB);

        assertThat(statusOf(HealthCheckCatalog.RES_RAWTABLE_GROWTH.key())).isEqualTo(HealthStatus.UP);
    }

    @Test
    void rawTableAtDegradedThresholdIsDegraded() {
        when(metricCollectorService.getLastRawAggTradeBytes()).thenReturn(3 * GB);

        assertThat(statusOf(HealthCheckCatalog.RES_RAWTABLE_GROWTH.key())).isEqualTo(HealthStatus.DEGRADED);
    }

    @Test
    void rawTableAtDownThresholdIsDown() {
        when(metricCollectorService.getLastRawAggTradeBytes()).thenReturn(6 * GB);

        assertThat(statusOf(HealthCheckCatalog.RES_RAWTABLE_GROWTH.key())).isEqualTo(HealthStatus.DOWN);
    }

    @Test
    void rawTableNeverCollectedIsUnknown() {
        when(metricCollectorService.getLastRawAggTradeBytes()).thenReturn(-1L);

        assertThat(statusOf(HealthCheckCatalog.RES_RAWTABLE_GROWTH.key())).isEqualTo(HealthStatus.UNKNOWN);
    }

    @Test
    void wsConnUnderDegradedThresholdIsUp() {
        when(metricCollectorService.getLastWsConnections()).thenReturn(120);

        assertThat(statusOf(HealthCheckCatalog.RES_WS_CONNECTIONS.key())).isEqualTo(HealthStatus.UP);
    }

    @Test
    void wsConnAtDegradedThresholdIsDegraded() {
        when(metricCollectorService.getLastWsConnections()).thenReturn(300);

        assertThat(statusOf(HealthCheckCatalog.RES_WS_CONNECTIONS.key())).isEqualTo(HealthStatus.DEGRADED);
    }

    @Test
    void wsConnAtDownThresholdIsDown() {
        when(metricCollectorService.getLastWsConnections()).thenReturn(800);

        assertThat(statusOf(HealthCheckCatalog.RES_WS_CONNECTIONS.key())).isEqualTo(HealthStatus.DOWN);
    }

    @Test
    void wsConnNeverCollectedIsUnknown() {
        when(metricCollectorService.getLastWsConnections()).thenReturn(-1);

        assertThat(statusOf(HealthCheckCatalog.RES_WS_CONNECTIONS.key())).isEqualTo(HealthStatus.UNKNOWN);
    }

    @Test
    void eventDerivedNoOpenEventIsUp() {
        // open 이벤트 없음(기본 null) → 정상(UP)
        assertThat(statusOf(HealthCheckCatalog.DATA_CANDLE_GAP.key())).isEqualTo(HealthStatus.UP);
    }

    @Test
    void eventDerivedOpenDegradedIsDegraded() {
        HealthCheckEvent open = new HealthCheckEvent();
        open.setStatus("DEGRADED");
        open.setCause("gap 2개");
        when(eventRepository.findTopByCheckKeyAndResolvedAtIsNullOrderByLastFailedAtDesc(
                HealthCheckCatalog.DATA_CANDLE_GAP.key())).thenReturn(open);

        assertThat(statusOf(HealthCheckCatalog.DATA_CANDLE_GAP.key())).isEqualTo(HealthStatus.DEGRADED);
    }

    @Test
    void eventDerivedOpenDownIsDown() {
        HealthCheckEvent open = new HealthCheckEvent();
        open.setStatus("DOWN");
        open.setCause("flat 40%");
        when(eventRepository.findTopByCheckKeyAndResolvedAtIsNullOrderByLastFailedAtDesc(
                HealthCheckCatalog.DATA_QUALITY.key())).thenReturn(open);

        assertThat(statusOf(HealthCheckCatalog.DATA_QUALITY.key())).isEqualTo(HealthStatus.DOWN);
    }

    @Test
    void externalNoEventIsUp() {
        // L6 외부연동: 호출 이력 없으면(open 없음) 정상(UP) — 결정1=가
        assertThat(statusOf(HealthCheckCatalog.EXT_TELEGRAM_SEND.key())).isEqualTo(HealthStatus.UP);
    }

    @Test
    void externalOpenFailureIsDown() {
        HealthCheckEvent open = new HealthCheckEvent();
        open.setStatus("DOWN");
        open.setCause("송신 실패");
        when(eventRepository.findTopByCheckKeyAndResolvedAtIsNullOrderByLastFailedAtDesc(
                HealthCheckCatalog.EXT_LLM.key())).thenReturn(open);

        assertThat(statusOf(HealthCheckCatalog.EXT_LLM.key())).isEqualTo(HealthStatus.DOWN);
    }

    private HealthStatus statusOf(String key) {
        when(eventRepository.findTop3ByCheckKeyOrderByLastFailedAtDesc(anyKey()))
                .thenReturn(List.of());
        List<HealthCheckView> checks = service.getChecks();
        return checks.stream()
                .filter(c -> c.key().equals(key))
                .findFirst()
                .map(HealthCheckView::status)
                .orElseThrow();
    }

    private static String anyKey() {
        return org.mockito.ArgumentMatchers.anyString();
    }
}
