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
