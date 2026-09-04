package com.chs.springboot.domain.binance.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BinanceKlineIntervalTest {

    @Test
    void exchangeBoundaryPointsToCurrentOpenCandleAndExcludesIt() {
        BinanceKlineInterval interval = BinanceKlineInterval.FIVE_MINUTES;

        assertThat(interval.latestClosedEndExclusive(12 * 60_000L + 30_000L))
                .isEqualTo(10 * 60_000L);
        assertThat(interval.label()).isEqualTo("5m");
        assertThat(interval.streamSegment()).isEqualTo("kline_5m");
    }

    @Test
    void oneDayUsesDailyBoundaryAndStreamSegment() {
        BinanceKlineInterval interval = BinanceKlineInterval.ONE_DAY;
        long oneDayMs = 24L * 60 * 60_000L;
        long exchangeTimeMs = 3L * oneDayMs + 1234L;

        assertThat(interval.latestClosedEndExclusive(exchangeTimeMs)).isEqualTo(3L * oneDayMs);
        assertThat(interval.label()).isEqualTo("1d");
        assertThat(interval.streamSegment()).isEqualTo("kline_1d");
    }
}
