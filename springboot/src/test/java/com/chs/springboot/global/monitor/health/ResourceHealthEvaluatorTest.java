package com.chs.springboot.global.monitor.health;

import com.chs.springboot.global.monitor.service.MetricCollectorService;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    void rawTableDown_marksFailCritical() {
        when(metric.getLastRawAggTradeBytes()).thenReturn(6 * GB);
        when(metric.getLastWsConnections()).thenReturn(-1); // UNKNOWN → skip

        evaluator.evaluate();

        verify(recorder).markFail(eq(RAWTABLE), eq(HealthStatus.DOWN), eq("CRITICAL"), anyString());
    }

    @Test
    void wsConnDegraded_marksFailWarn() {
        when(metric.getLastRawAggTradeBytes()).thenReturn(-1L); // UNKNOWN → skip
        when(metric.getLastWsConnections()).thenReturn(300);

        evaluator.evaluate();

        verify(recorder).markFail(eq(WSCONN), eq(HealthStatus.DEGRADED), eq("WARN"), anyString());
    }

    @Test
    void healthy_marksOk() {
        when(metric.getLastRawAggTradeBytes()).thenReturn(1 * GB);
        when(metric.getLastWsConnections()).thenReturn(50);

        evaluator.evaluate();

        verify(recorder).markOk(RAWTABLE);
        verify(recorder).markOk(WSCONN);
    }

    @Test
    void notCollected_recordsNothing() {
        when(metric.getLastRawAggTradeBytes()).thenReturn(-1L);
        when(metric.getLastWsConnections()).thenReturn(-1);

        evaluator.evaluate();

        verify(recorder, never()).markFail(anyString(), org.mockito.ArgumentMatchers.any(), anyString(), anyString());
        verify(recorder, never()).markOk(anyString());
    }
}
