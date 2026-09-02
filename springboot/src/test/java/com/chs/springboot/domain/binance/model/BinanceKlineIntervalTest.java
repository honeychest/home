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
}
