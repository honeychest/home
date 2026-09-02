package com.chs.springboot.domain.binance.analysis;

import com.chs.springboot.domain.binance.model.BinanceKline;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KlineSeriesValidatorTest {

    @Test
    void rejectsUnsortedCandles() {
        assertThatThrownBy(() -> KlineSeriesValidator.validate(List.of(kline(60_000), kline(0)), 60_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("정렬되지 않았거나 중복");
    }

    @Test
    void rejectsDuplicateCandles() {
        assertThatThrownBy(() -> KlineSeriesValidator.validate(List.of(kline(0), kline(0)), 60_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("정렬되지 않았거나 중복");
    }

    @Test
    void rejectsMissingInterval() {
        assertThatThrownBy(() -> KlineSeriesValidator.validate(List.of(kline(0), kline(120_000)), 60_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("결측");
    }

    private static BinanceKline kline(long openTimeMs) {
        BigDecimal price = BigDecimal.ONE;
        return new BinanceKline(openTimeMs, price, price, price, price, price,
                openTimeMs + 59_999, price, 1, price, price);
    }
}
