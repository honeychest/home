package com.chs.springboot.global.monitor.health;

import com.chs.springboot.global.redis.LeaderElectionService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

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

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final HealthCheckRecorder recorder = mock(HealthCheckRecorder.class);
    private final LeaderElectionService leader = mock(LeaderElectionService.class);
    private final DataIntegrityEvaluator evaluator = new DataIntegrityEvaluator(jdbc, recorder, leader);

    private void stubGapPresent(int present) {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any()))
                .thenReturn(present);
    }

    private void stubQuality(long total, long flat) {
        when(jdbc.queryForMap(anyString(), any(), any(), any(), any()))
                .thenReturn(Map.of("total", total, "flat", flat));
    }

    @Test
    void nonLeader_doesNothing() {
        when(leader.isLeader()).thenReturn(false);

        evaluator.evaluate();

        verifyNoInteractions(jdbc);
        verifyNoInteractions(recorder);
    }

    @Test
    void gapAboveDownThreshold_recordsDown() {
        when(leader.isLeader()).thenReturn(true);
        stubGapPresent(55);          // 누락 5개 ≥ 3 → DOWN
        stubQuality(60, 0);          // quality 정상

        evaluator.evaluate();

        verify(recorder).record(eq(GAP), eq(HealthStatus.DOWN), anyString());
        verify(recorder).record(eq(QUALITY), eq(HealthStatus.UP), anyString());
    }

    @Test
    void flatRatioAboveDownThreshold_recordsDown() {
        when(leader.isLeader()).thenReturn(true);
        stubGapPresent(60);          // gap 정상
        stubQuality(100, 40);        // flat 40% ≥ 30 → DOWN

        evaluator.evaluate();

        verify(recorder).record(eq(GAP), eq(HealthStatus.UP), anyString());
        verify(recorder).record(eq(QUALITY), eq(HealthStatus.DOWN), anyString());
    }

    @Test
    void allHealthy_recordsUp() {
        when(leader.isLeader()).thenReturn(true);
        stubGapPresent(60);          // 누락 0 → UP
        stubQuality(60, 3);          // flat 5% → UP

        evaluator.evaluate();

        verify(recorder).record(eq(GAP), eq(HealthStatus.UP), anyString());
        verify(recorder).record(eq(QUALITY), eq(HealthStatus.UP), anyString());
    }

    @Test
    void noSample_qualityNotRecorded() {
        when(leader.isLeader()).thenReturn(true);
        stubGapPresent(60);          // gap UP
        stubQuality(0, 0);           // 표본 없음 → quality 판정 보류

        evaluator.evaluate();

        verify(recorder).record(eq(GAP), eq(HealthStatus.UP), anyString());
        // quality 는 어떤 기록도 하지 않음
        verify(recorder, never()).record(eq(QUALITY), any(), any());
    }
}
