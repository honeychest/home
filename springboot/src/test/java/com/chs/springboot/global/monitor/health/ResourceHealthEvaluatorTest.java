package com.chs.springboot.global.monitor.health;

import com.chs.springboot.global.monitor.service.MetricCollectorService;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResourceHealthEvaluatorTest {

    private static final long GB = 1024L * 1024 * 1024;
    private static final String RAWTABLE = HealthCheckCatalog.RES_RAWTABLE_GROWTH.key();
    private static final String WSCONN = HealthCheckCatalog.RES_WS_CONNECTIONS.key();

    private final MetricCollectorService metric = mock(MetricCollectorService.class);
    private final HealthCheckRecorder recorder = mock(HealthCheckRecorder.class);
    private final ResourceHealthEvaluator evaluator = new ResourceHealthEvaluator(metric, recorder);

    @Test
    void rawTableDown_recordsDown() {
        when(metric.getLastRawAggTradeBytes()).thenReturn(6 * GB);
        when(metric.getLastWsConnections()).thenReturn(-1); // 미수집 → UNKNOWN

        evaluator.evaluate();

        verify(recorder).record(eq(RAWTABLE), eq(HealthStatus.DOWN), anyString());
    }

    @Test
    void wsConnDegraded_recordsDegraded() {
        when(metric.getLastRawAggTradeBytes()).thenReturn(-1L); // 미수집 → UNKNOWN
        when(metric.getLastWsConnections()).thenReturn(300);

        evaluator.evaluate();

        verify(recorder).record(eq(WSCONN), eq(HealthStatus.DEGRADED), anyString());
    }

    @Test
    void healthy_recordsUp() {
        when(metric.getLastRawAggTradeBytes()).thenReturn(1 * GB);
        when(metric.getLastWsConnections()).thenReturn(50);

        evaluator.evaluate();

        verify(recorder).record(eq(RAWTABLE), eq(HealthStatus.UP), anyString());
        verify(recorder).record(eq(WSCONN), eq(HealthStatus.UP), anyString());
    }

    @Test
    void notCollected_passesUnknownToRecorder() {
        // 미수집(-1) 무시 정책은 recorder.record 가 담당 — 평가기는 UNKNOWN 을 그대로 전달한다
        when(metric.getLastRawAggTradeBytes()).thenReturn(-1L);
        when(metric.getLastWsConnections()).thenReturn(-1);

        evaluator.evaluate();

        verify(recorder).record(eq(RAWTABLE), eq(HealthStatus.UNKNOWN), anyString());
        verify(recorder).record(eq(WSCONN), eq(HealthStatus.UNKNOWN), anyString());
    }
}
