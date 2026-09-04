package com.chs.springboot.domain.analysis.service;

import com.chs.springboot.domain.analysis.model.AnalysisTemplate;
import com.chs.springboot.domain.analysis.repository.AnalysisTemplateRepository;
import com.chs.springboot.domain.binance.service.BinanceKlineWindow;
import com.chs.springboot.domain.binance.service.SignalCandleSource;
import com.chs.springboot.domain.binance.service.SignalSseService;
import com.chs.springboot.global.monitor.health.HealthHeartbeat;
import com.chs.springboot.global.redis.LeaderElectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AnalysisDetectionSchedulerTest {

    @Test
    void skipsDetectionWhenRecentWindowHasMissingMinute() {
        AnalysisTemplateRepository templates = mock(AnalysisTemplateRepository.class);
        AnalysisDetectionEngine engine = mock(AnalysisDetectionEngine.class);
        SignalSseService sse = mock(SignalSseService.class);
        LeaderElectionService leader = mock(LeaderElectionService.class);
        SignalCandleSource source = mock(SignalCandleSource.class);
        HealthHeartbeat heartbeat = mock(HealthHeartbeat.class);
        AnalysisDetectionScheduler scheduler = new AnalysisDetectionScheduler(
                templates, engine, sse, leader, source, new ObjectMapper(), heartbeat);
        AnalysisTemplate template = new AnalysisTemplate();
        template.setConditions("{}");

        when(leader.isLeader()).thenReturn(true);
        when(templates.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(template));
        when(source.find(anyString(), eq(SignalCandleSource.Interval.ONE_MINUTE), any(Long.class),
                any(Long.class), eq(SignalCandleSource.QueryMode.COMPLETED)))
                .thenReturn(window(false));

        scheduler.run();

        verifyNoInteractions(engine, sse);
        verify(heartbeat).beat(anyString());
    }

    @Test
    void evaluatesContinuousWindowAfterIngestLag() {
        AnalysisTemplateRepository templates = mock(AnalysisTemplateRepository.class);
        AnalysisDetectionEngine engine = mock(AnalysisDetectionEngine.class);
        SignalSseService sse = mock(SignalSseService.class);
        LeaderElectionService leader = mock(LeaderElectionService.class);
        SignalCandleSource source = mock(SignalCandleSource.class);
        HealthHeartbeat heartbeat = mock(HealthHeartbeat.class);
        AnalysisDetectionScheduler scheduler = new AnalysisDetectionScheduler(
                templates, engine, sse, leader, source, new ObjectMapper(), heartbeat);
        AnalysisTemplate template = new AnalysisTemplate();
        template.setConditions("{}");

        when(leader.isLeader()).thenReturn(true);
        when(templates.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(template));
        when(source.find(anyString(), eq(SignalCandleSource.Interval.ONE_MINUTE), any(Long.class),
                any(Long.class), eq(SignalCandleSource.QueryMode.COMPLETED)))
                .thenReturn(window(true));
        when(engine.evaluate(anyList(), any())).thenReturn(List.of());

        scheduler.run();

        verify(engine, times(2)).evaluate(anyList(), any());
        verify(heartbeat).beat(anyString());
    }

    private List<SignalCandleSource.SignalCandle> window(boolean complete) {
        long end = BinanceKlineWindow.safeEnd(System.currentTimeMillis());
        long from = end - 1_440L * 60_000L;
        List<SignalCandleSource.SignalCandle> candles = new ArrayList<>(1_440);
        for (int i = 0; i < 1_440; i++) {
            if (!complete && i == 500) {
                continue;
            }
            long timeMs = from + i * 60_000L;
            candles.add(new SignalCandleSource.SignalCandle(
                    "BTCUSDT", timeMs, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ONE,
                    BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO));
        }
        return candles;
    }
}
