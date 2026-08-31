package com.chs.springboot.global.monitor.health;

import com.chs.springboot.global.monitor.service.MetricCollectorService;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResourceHealthEvaluatorTest {

    private static final String WSCONN = HealthCheckCatalog.RES_WS_CONNECTIONS.key();

    private final MetricCollectorService metric = mock(MetricCollectorService.class);
    private final HealthCheckRecorder recorder = mock(HealthCheckRecorder.class);
    private final ResourceHealthEvaluator evaluator = new ResourceHealthEvaluator(metric, recorder);

    @Test
    void wsConnDegraded_recordsDegraded() {
        when(metric.getLastWsConnections()).thenReturn(300);

        evaluator.evaluate();

        verify(recorder).record(eq(WSCONN), eq(HealthStatus.DEGRADED), anyString());
    }

    @Test
    void healthy_recordsUp() {
        when(metric.getLastWsConnections()).thenReturn(50);

        evaluator.evaluate();

        verify(recorder).record(eq(WSCONN), eq(HealthStatus.UP), anyString());
    }

    @Test
    void notCollected_passesUnknownToRecorder() {
        // 미수집(-1) 무시 정책은 recorder.record 가 담당 — 평가기는 UNKNOWN 을 그대로 전달한다
        when(metric.getLastWsConnections()).thenReturn(-1);

        evaluator.evaluate();

        verify(recorder).record(eq(WSCONN), eq(HealthStatus.UNKNOWN), anyString());
    }
}
