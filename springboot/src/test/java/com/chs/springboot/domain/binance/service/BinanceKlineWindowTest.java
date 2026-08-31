package com.chs.springboot.domain.binance.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinanceKlineWindowTest {

    @Test
    void seedsOnlyTheRecentConfiguredHoursAndExcludesCurrentCandle() {
        long nowMs = 48L * BinanceKlineWindow.HOUR_MS + 12_345L;

        BinanceKlineWindow window = BinanceKlineWindow.fromLastCandle(null, nowMs, 48L);

        assertEquals(0L, window.startTimeMs());
        assertEquals(48L * BinanceKlineWindow.HOUR_MS - BinanceKlineWindow.SAFE_DELAY_MS,
                window.endTimeMsExclusive());
    }

    @Test
    void startsAfterTheLastStoredCandle() {
        long nowMs = 102L * BinanceKlineWindow.INTERVAL_MS + 9_999L;

        BinanceKlineWindow window = BinanceKlineWindow.fromLastCandle(98L * BinanceKlineWindow.INTERVAL_MS, nowMs, 48L);

        assertEquals(99L * BinanceKlineWindow.INTERVAL_MS, window.startTimeMs());
        assertEquals(100L * BinanceKlineWindow.INTERVAL_MS, window.endTimeMsExclusive());
        assertTrue(window.nextPageStart(99L * BinanceKlineWindow.INTERVAL_MS) > window.startTimeMs());
    }

    @Test
    void hasNoRangeWhenStoredDataIsCurrent() {
        long nowMs = 102L * BinanceKlineWindow.INTERVAL_MS + 9_999L;

        BinanceKlineWindow window = BinanceKlineWindow.fromLastCandle(99L * BinanceKlineWindow.INTERVAL_MS, nowMs, 48L);

        assertTrue(window.isEmpty());
    }
}
