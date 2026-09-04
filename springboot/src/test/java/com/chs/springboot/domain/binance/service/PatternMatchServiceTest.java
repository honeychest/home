package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.repository.SignalParamsRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PatternMatchServiceTest {

    @Test
    void getPatternUsesNormalizedSymbolAndSharedCandleSource() {
        SignalCandleSource candleSource = mock(SignalCandleSource.class);
        SignalParamsRepository paramsRepository = mock(SignalParamsRepository.class);
        when(candleSource.find(eq("BTCUSDT"), eq(SignalCandleSource.Interval.FIVE_MINUTES),
                anyLong(), anyLong(), eq(SignalCandleSource.QueryMode.COMPLETED)))
                .thenReturn(List.of(candle(1_700_000_000_000L)));

        var result = new PatternMatchService(candleSource, paramsRepository)
                .getPattern("btc", 1_700_000_000_000L);

        assertFalse((Boolean) result.get("triggered"));
        verify(paramsRepository).findById("BTCUSDT");
        verify(candleSource).find(eq("BTCUSDT"), eq(SignalCandleSource.Interval.FIVE_MINUTES),
                anyLong(), anyLong(), eq(SignalCandleSource.QueryMode.COMPLETED));
    }

    private static SignalCandleSource.SignalCandle candle(long timeMs) {
        BigDecimal price = BigDecimal.valueOf(100);
        return new SignalCandleSource.SignalCandle(
                "BTCUSDT", timeMs, price, price, price, price,
                BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ZERO);
    }
}
