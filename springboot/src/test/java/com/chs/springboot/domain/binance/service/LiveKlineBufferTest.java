package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.BinanceKline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LiveKlineBufferTest {

    @Test
    @DisplayName("seed 후 snapshot은 시각순으로 반환된다")
    void seed_returnsOrderedSnapshot() {
        LiveKlineBuffer buffer = new LiveKlineBuffer(10);

        buffer.seed(List.of(kline(2), kline(1), kline(3)));

        List<BinanceKline> closed = buffer.snapshot().closedCandles();
        assertThat(closed).extracting(BinanceKline::openTimeMs).containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("최대 크기를 넘으면 가장 오래된 캔들부터 제거된다")
    void appendClosed_evictsOldestWhenOverCapacity() {
        LiveKlineBuffer buffer = new LiveKlineBuffer(2);
        buffer.seed(List.of(kline(1), kline(2)));

        buffer.appendClosed(kline(3));

        List<BinanceKline> closed = buffer.snapshot().closedCandles();
        assertThat(closed).extracting(BinanceKline::openTimeMs).containsExactly(2L, 3L);
    }

    @Test
    @DisplayName("같은 시각의 캔들이 다시 오면 교체된다")
    void appendClosed_sameOpenTime_replaces() {
        LiveKlineBuffer buffer = new LiveKlineBuffer(10);
        buffer.seed(List.of(kline(1)));

        buffer.appendClosed(kline(1, "999"));

        List<BinanceKline> closed = buffer.snapshot().closedCandles();
        assertThat(closed).hasSize(1);
        assertThat(closed.get(0).closePrice()).isEqualByComparingTo("999");
    }

    @Test
    @DisplayName("진행 중인 봉은 확정봉 목록에 안 들어가고, 확정되면 사라진다")
    void updatePartial_thenAppendClosed_clearsPartial() {
        LiveKlineBuffer buffer = new LiveKlineBuffer(10);

        buffer.updatePartial(kline(5));
        assertThat(buffer.snapshot().currentPartial().openTimeMs()).isEqualTo(5L);
        assertThat(buffer.snapshot().closedCandles()).isEmpty();

        buffer.appendClosed(kline(5));
        assertThat(buffer.snapshot().currentPartial()).isNull();
        assertThat(buffer.snapshot().closedCandles()).hasSize(1);
    }

    private static BinanceKline kline(long openTimeMs) {
        return kline(openTimeMs, "100");
    }

    private static BinanceKline kline(long openTimeMs, String closePrice) {
        return new BinanceKline(
                openTimeMs,
                new BigDecimal("100"),
                new BigDecimal("110"),
                new BigDecimal("90"),
                new BigDecimal(closePrice),
                new BigDecimal("1"),
                openTimeMs + 60_000,
                new BigDecimal("100"),
                1L,
                new BigDecimal("0.5"),
                new BigDecimal("50")
        );
    }
}
