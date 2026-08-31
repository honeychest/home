package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.BinanceKline;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BinanceKlineRangeFetcherTest {

    private final BinanceKlineRestClient restClient = mock(BinanceKlineRestClient.class);

    @Test
    void walksFullPagesAndAdvancesByOneMinute() {
        long toMs = 1_002L * BinanceKlineRangeFetcher.INTERVAL_MS;
        List<BinanceKline> firstPage = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            firstPage.add(kline(i * BinanceKlineRangeFetcher.INTERVAL_MS));
        }
        List<BinanceKline> secondPage = List.of(
                kline(1_000L * BinanceKlineRangeFetcher.INTERVAL_MS),
                kline(1_001L * BinanceKlineRangeFetcher.INTERVAL_MS));
        when(restClient.fetchPage(eq("BTCUSDT"), eq("SPOT"), anyLong(), eq(toMs)))
                .thenReturn(firstPage, secondPage);

        BinanceKlineRangeFetcher.RangeResult result = new BinanceKlineRangeFetcher(restClient)
                .fetch("BTCUSDT", "SPOT", 0L, toMs);

        assertEquals(1_002, result.klines().size());
        assertEquals(2, result.pages());
        assertFalse(result.firstPageEmpty());
    }

    @Test
    void filtersOutOfRangeRowsButRejectsDuplicateAndReverseRows() {
        when(restClient.fetchPage(eq("BTCUSDT"), eq("SPOT"), eq(0L), eq(120_000L)))
                .thenReturn(List.of(kline(-60_000L), kline(0L), kline(60_000L), kline(120_000L)));

        BinanceKlineRangeFetcher.RangeResult result = new BinanceKlineRangeFetcher(restClient)
                .fetch("BTCUSDT", "SPOT", 0L, 120_000L);

        assertEquals(List.of(0L, 60_000L), result.openTimes());

        when(restClient.fetchPage(eq("BTCUSDT"), eq("FUTURES"), eq(0L), eq(120_000L)))
                .thenReturn(List.of(kline(0L), kline(0L)));
        assertThrows(IllegalStateException.class, () -> new BinanceKlineRangeFetcher(restClient)
                .fetch("BTCUSDT", "FUTURES", 0L, 120_000L));

        when(restClient.fetchPage(eq("ENAUSDT"), eq("FUTURES"), eq(0L), eq(120_000L)))
                .thenReturn(List.of(kline(60_000L), kline(0L)));
        assertThrows(IllegalStateException.class, () -> new BinanceKlineRangeFetcher(restClient)
                .fetch("ENAUSDT", "FUTURES", 0L, 120_000L));

        when(restClient.fetchPage(eq("ETHUSDT"), eq("SPOT"), eq(0L), eq(180_000L)))
                .thenReturn(List.of(kline(0L), kline(120_000L)));
        assertThrows(IllegalStateException.class, () -> new BinanceKlineRangeFetcher(restClient)
                .fetch("ETHUSDT", "SPOT", 0L, 180_000L));
    }

    @Test
    void marksEmptyFirstResponseInsteadOfTreatingItAsNoGap() {
        when(restClient.fetchPage(eq("BTCUSDT"), eq("SPOT"), eq(0L), eq(60_000L)))
                .thenReturn(List.of());

        BinanceKlineRangeFetcher.RangeResult result = new BinanceKlineRangeFetcher(restClient)
                .fetch("BTCUSDT", "SPOT", 0L, 60_000L);

        assertTrue(result.firstPageEmpty());
        assertTrue(result.openTimes().isEmpty());
    }

    @Test
    void propagatesFailureAfterACompletedPage() {
        long toMs = 1_002L * BinanceKlineRangeFetcher.INTERVAL_MS;
        List<BinanceKline> firstPage = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            firstPage.add(kline(i * BinanceKlineRangeFetcher.INTERVAL_MS));
        }
        when(restClient.fetchPage(eq("BTCUSDT"), eq("SPOT"), anyLong(), eq(toMs)))
                .thenReturn(firstPage)
                .thenThrow(new IllegalStateException("HTTP timeout"));

        assertThrows(IllegalStateException.class, () -> new BinanceKlineRangeFetcher(restClient)
                .fetch("BTCUSDT", "SPOT", 0L, toMs));
    }

    @Test
    void rejectsEmptyResponseAfterAFullPage() {
        long toMs = 1_002L * BinanceKlineRangeFetcher.INTERVAL_MS;
        List<BinanceKline> firstPage = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            firstPage.add(kline(i * BinanceKlineRangeFetcher.INTERVAL_MS));
        }
        when(restClient.fetchPage(eq("ENAUSDT"), eq("SPOT"), anyLong(), eq(toMs)))
                .thenReturn(firstPage)
                .thenReturn(List.of());

        assertThrows(IllegalStateException.class, () -> new BinanceKlineRangeFetcher(restClient)
                .fetch("ENAUSDT", "SPOT", 0L, toMs));
    }

    @Test
    void rejectsRangesLongerThan48HoursAtFetcherBoundary() {
        assertThrows(IllegalArgumentException.class, () -> new BinanceKlineRangeFetcher(restClient)
                .fetch("BTCUSDT", "SPOT", 0L, BinanceKlineRangeFetcher.MAX_RANGE_MS + 60_000L));
    }

    private BinanceKline kline(long openTimeMs) {
        return new BinanceKline(
                openTimeMs,
                new BigDecimal("10"),
                new BigDecimal("12"),
                new BigDecimal("9"),
                new BigDecimal("11"),
                new BigDecimal("2"),
                openTimeMs + 59_999L,
                new BigDecimal("20"),
                3L,
                new BigDecimal("1"),
                new BigDecimal("10"));
    }
}
