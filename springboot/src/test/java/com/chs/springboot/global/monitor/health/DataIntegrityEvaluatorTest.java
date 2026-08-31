package com.chs.springboot.global.monitor.health;

import com.chs.springboot.domain.binance.service.SignalCandleSource;
import com.chs.springboot.global.redis.LeaderElectionService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DataIntegrityEvaluatorTest {

    private static final String GAP = HealthCheckCatalog.DATA_CANDLE_GAP.key();
    private static final String QUALITY = HealthCheckCatalog.DATA_QUALITY.key();

    private final SignalCandleSource candleSource = mock(SignalCandleSource.class);
    private final HealthCheckRecorder recorder = mock(HealthCheckRecorder.class);
    private final LeaderElectionService leader = mock(LeaderElectionService.class);
    private final DataIntegrityEvaluator evaluator = new DataIntegrityEvaluator(candleSource, recorder, leader);

    private void stubCandles(int present, int flat) {
        List<SignalCandleSource.SignalCandle> candles = new ArrayList<>(present);
        for (int i = 0; i < present; i++) {
            BigDecimal price = BigDecimal.valueOf(i + 100L);
            BigDecimal high = i < flat ? price : price.add(BigDecimal.ONE);
            candles.add(new SignalCandleSource.SignalCandle(
                    "BTCUSDT", i * 60_000L, price, high, price, price,
                    BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ZERO));
        }
        when(candleSource.find(any(), eq(SignalCandleSource.Interval.ONE_MINUTE), any(Long.class),
                any(Long.class), eq(SignalCandleSource.QueryMode.COMPLETED))).thenReturn(candles);
    }

    @Test
    void nonLeader_doesNothing() {
        when(leader.isLeader()).thenReturn(false);

        evaluator.evaluate();

        verifyNoInteractions(candleSource);
        verifyNoInteractions(recorder);
    }

    @Test
    void gapAboveDownThreshold_recordsDown() {
        when(leader.isLeader()).thenReturn(true);
        stubCandles(55, 0);          // 누락 5개 ≥ 3 → DOWN

        evaluator.evaluate();

        verify(recorder).record(eq(GAP), eq(HealthStatus.DOWN), anyString());
        verify(recorder).record(eq(QUALITY), eq(HealthStatus.UP), anyString());
    }

    @Test
    void flatRatioAboveDownThreshold_recordsDown() {
        when(leader.isLeader()).thenReturn(true);
        stubCandles(60, 40);          // flat 40% ≥ 30 → DOWN

        evaluator.evaluate();

        verify(recorder).record(eq(GAP), eq(HealthStatus.UP), anyString());
        verify(recorder).record(eq(QUALITY), eq(HealthStatus.DOWN), anyString());
    }

    @Test
    void allHealthy_recordsUp() {
        when(leader.isLeader()).thenReturn(true);
        stubCandles(60, 3);          // 누락 0 → UP, flat 5% → UP

        evaluator.evaluate();

        verify(recorder).record(eq(GAP), eq(HealthStatus.UP), anyString());
        verify(recorder).record(eq(QUALITY), eq(HealthStatus.UP), anyString());
    }

    @Test
    void noSample_qualityNotRecorded() {
        when(leader.isLeader()).thenReturn(true);
        stubCandles(0, 0);           // 표본 없음 → quality 판정 보류

        evaluator.evaluate();

        verify(recorder).record(eq(GAP), eq(HealthStatus.DOWN), anyString());
        // quality 는 어떤 기록도 하지 않음
        verify(recorder, never()).record(eq(QUALITY), any(), any());
    }
}
