package com.chs.springboot.domain.analysis.service;

import com.chs.springboot.domain.analysis.repository.AnalysisTemplateRepository;
import com.chs.springboot.domain.binance.service.SignalCandleSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AnalysisTemplateServiceTest {

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    private AnalysisTemplateService service(SignalCandleSource candleSource) {
        return new AnalysisTemplateService(
                mock(AnalysisTemplateRepository.class), candleSource,
                mock(AnalysisDetectionEngine.class), new ObjectMapper());
    }

    @Test
    void getDeltaRejectsFiveMinuteRangeLongerThan90Days() {
        SignalCandleSource candleSource = mock(SignalCandleSource.class);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                service(candleSource).getDelta("BTC", 0L, 91 * DAY_MS, "5m"));

        assertEquals("5분/15분 delta 조회 범위는 최대 90일입니다", error.getMessage());
        verifyNoInteractions(candleSource);
    }

    @Test
    void getDeltaRejectsFifteenMinuteRangeLongerThan90Days() {
        SignalCandleSource candleSource = mock(SignalCandleSource.class);

        assertThrows(IllegalArgumentException.class, () ->
                service(candleSource).getDelta("BTC", 0L, 91 * DAY_MS, "15m"));

        verifyNoInteractions(candleSource);
    }

    @Test
    void getDeltaAllowsFiveMinuteRangeUpTo90Days() {
        SignalCandleSource candleSource = mock(SignalCandleSource.class);
        when(candleSource.find(anyString(), any(), anyLong(), anyLong(), any())).thenReturn(List.of());

        List<Map<String, Object>> result = service(candleSource).getDelta("BTC", 0L, 90 * DAY_MS, "5m");

        assertEquals(List.of(), result);
    }

    @Test
    void getDeltaDoesNotCapOneMinuteRange() {
        SignalCandleSource candleSource = mock(SignalCandleSource.class);
        when(candleSource.find(anyString(), any(), anyLong(), anyLong(), any())).thenReturn(List.of());

        // 1분 인터벌은 이번 범위(kline_5m 마이그레이션) 밖 — 상한을 새로 걸지 않는다.
        List<Map<String, Object>> result = service(candleSource).getDelta("BTC", 0L, 365 * DAY_MS, "1m");

        assertEquals(List.of(), result);
    }
}
