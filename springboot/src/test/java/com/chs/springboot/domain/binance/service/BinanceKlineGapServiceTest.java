package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.AggTradeCollectStatus;
import com.chs.springboot.domain.binance.model.BinanceKline;
import com.chs.springboot.domain.binance.model.BinanceKline5m;
import com.chs.springboot.domain.binance.model.BinanceKlineInterval;
import com.chs.springboot.domain.binance.repository.AggTradeCollectStatusRepository;
import com.chs.springboot.domain.binance.repository.BinanceKline5mRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BinanceKlineGapServiceTest {

    private final AggTradeCollectStatusRepository statusRepository = mock(AggTradeCollectStatusRepository.class);
    private final BinanceKline5mRepository canonicalRepository = mock(BinanceKline5mRepository.class);
    private final BinanceKlineRangeFetcher rangeFetcher = mock(BinanceKlineRangeFetcher.class);
    private final Clock clock = Clock.fixed(Instant.ofEpochMilli(2L * 86_400_000L + 900_000L), ZoneOffset.UTC);

    @Test
    void groupsOnlyConsecutiveMissingKlinesAndUsesExclusiveEnd() {
        when(statusRepository.findByEnabledTrue()).thenReturn(List.of(status("BTCUSDT", "SPOT")));
        when(canonicalRepository.findBySymbolAndMarketTypeAndCandleTimeMsGreaterThanEqualAndCandleTimeMsLessThanOrderByCandleTimeMsAsc(
                eq("BTCUSDT"), eq("SPOT"), eq(0L), eq(1_200_000L)))
                .thenReturn(List.of(candle(300_000L)));
        when(rangeFetcher.fetch("BTCUSDT", "SPOT", 0L, 1_200_000L))
                .thenReturn(new BinanceKlineRangeFetcher.RangeResult(
                        List.of(kline(0L), kline(300_000L), kline(600_000L), kline(900_000L)), false, 1));

        List<Map<String, Object>> result = new BinanceKlineGapService(
                statusRepository, canonicalRepository, rangeFetcher, clock)
                .findGaps(null, 0L, 1_200_000L);

        assertEquals(2, result.size());
        assertEquals(0L, result.get(0).get("gap_start_ms"));
        assertEquals(300_000L, result.get(0).get("gap_end_ms"));
        assertEquals(1, result.get(0).get("missing_candles"));
        assertEquals(600_000L, result.get(1).get("gap_start_ms"));
        assertEquals(1_200_000L, result.get(1).get("gap_end_ms"));
        assertEquals(2, result.get(1).get("missing_candles"));
        assertEquals("GAP", result.get(1).get("status"));
    }

    @Test
    void reportsEmptyAndPartialFailuresInsteadOfReturningAnEmptyGapList() {
        when(statusRepository.findByEnabledTrue()).thenReturn(List.of(
                status("BTCUSDT", "SPOT"), status("ENAUSDT", "FUTURES")));
        when(canonicalRepository.findBySymbolAndMarketTypeAndCandleTimeMsGreaterThanEqualAndCandleTimeMsLessThanOrderByCandleTimeMsAsc(
                anyLongString(), anyLongString(), eq(0L), eq(300_000L))).thenReturn(List.of());
        when(rangeFetcher.fetch("BTCUSDT", "SPOT", 0L, 300_000L))
                .thenReturn(new BinanceKlineRangeFetcher.RangeResult(List.of(), true, 1));
        when(rangeFetcher.fetch("ENAUSDT", "FUTURES", 0L, 300_000L))
                .thenThrow(new IllegalStateException("page did not advance"));

        List<Map<String, Object>> result = new BinanceKlineGapService(
                statusRepository, canonicalRepository, rangeFetcher, clock)
                .findGaps(null, 0L, 300_000L);

        assertEquals(2, result.size());
        assertEquals("ERROR", result.get(0).get("status"));
        assertEquals("ERROR", result.get(1).get("status"));
    }

    @Test
    void defaultsToTwoDaysAndRejectsLongOrUnsafeRanges() {
        when(statusRepository.findByEnabledTrue()).thenReturn(List.of(status("BTCUSDT", "SPOT")));
        long safeEnd = BinanceKlineWindow.safeEnd(clock.millis(), BinanceKlineInterval.FIVE_MINUTES);
        when(canonicalRepository.findBySymbolAndMarketTypeAndCandleTimeMsGreaterThanEqualAndCandleTimeMsLessThanOrderByCandleTimeMsAsc(
                eq("BTCUSDT"), eq("SPOT"), anyLong(), anyLong())).thenReturn(List.of());
        when(rangeFetcher.fetch(eq("BTCUSDT"), eq("SPOT"), anyLong(), anyLong()))
                .thenReturn(new BinanceKlineRangeFetcher.RangeResult(List.of(kline(safeEnd - 300_000L)), false, 1));
        BinanceKlineGapService service = new BinanceKlineGapService(
                statusRepository, canonicalRepository, rangeFetcher, clock);

        service.findGaps(null, null, null);
        org.mockito.Mockito.verify(rangeFetcher).fetch(
                "BTCUSDT", "SPOT", safeEnd - 2L * 86_400_000L, safeEnd);
        org.mockito.Mockito.verify(statusRepository).findByEnabledTrue();

        assertThrows(IllegalArgumentException.class,
                () -> service.findGaps(null, 0L, 49L * 60L * 60L * 1000L));
        assertThrows(IllegalArgumentException.class,
                () -> service.findGaps(null, 0L, safeEnd + 300_000L));
    }

    private static String anyLongString() {
        return org.mockito.ArgumentMatchers.anyString();
    }

    private AggTradeCollectStatus status(String symbol, String marketType) {
        AggTradeCollectStatus status = new AggTradeCollectStatus();
        status.setSymbol(symbol);
        status.setMarketType(marketType);
        status.setEnabled(true);
        return status;
    }

    private BinanceKline5m candle(long timeMs) {
        BinanceKline5m candle = new BinanceKline5m();
        candle.setCandleTimeMs(timeMs);
        return candle;
    }

    private BinanceKline kline(long openTimeMs) {
        return new BinanceKline(openTimeMs, new BigDecimal("10"), new BigDecimal("12"),
                new BigDecimal("9"), new BigDecimal("11"), new BigDecimal("2"),
                openTimeMs + 59_999L, new BigDecimal("20"), 3L,
                new BigDecimal("1"), new BigDecimal("10"));
    }
}
