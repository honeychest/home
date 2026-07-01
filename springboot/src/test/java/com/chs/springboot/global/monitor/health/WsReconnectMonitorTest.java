package com.chs.springboot.global.monitor.health;

import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WsReconnectMonitorTest {

    private static final String KEY = HealthCheckCatalog.FEED_WS_RECONNECT.key();

    private final HealthCheckRecorder recorder = mock(HealthCheckRecorder.class);
    private final WsReconnectMonitor monitor = new WsReconnectMonitor(recorder);

    @Test
    void noReconnect_marksOk() {
        monitor.evaluate();

        verify(recorder).markOk(KEY);
    }

    @Test
    void fewReconnects_marksDegraded() {
        for (int i = 0; i < 3; i++) monitor.record("BinanceStream/ticker"); // ≥3 → 경고

        monitor.evaluate();

        verify(recorder).markFail(eq(KEY), eq(HealthStatus.DEGRADED), eq("WARN"), anyString());
    }

    @Test
    void manyReconnects_marksDown() {
        for (int i = 0; i < 6; i++) monitor.record("UpbitStream/ticker"); // ≥6 → 다운

        monitor.evaluate();

        verify(recorder).markFail(eq(KEY), eq(HealthStatus.DOWN), eq("CRITICAL"), anyString());
    }
}
