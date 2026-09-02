package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.BinanceKline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SupertrendCalculatorTest {

    @Test
    @DisplayName("데이터가 period+1개 미만이면 null")
    void calculate_insufficientData_returnsNull() {
        List<BinanceKline> candles = trending(100, 1, 5);

        assertThat(SupertrendCalculator.calculate(candles, 10, 3)).isNull();
    }

    @Test
    @DisplayName("꾸준히 좁은 변동폭으로 상승하면 상승 추세, 값은 현재가보다 낮음(지지선)")
    void calculate_uptrend_belowPrice() {
        List<BinanceKline> candles = trending(100, 1, 40);
        BigDecimal lastClose = candles.get(candles.size() - 1).closePrice();

        SupertrendCalculator.Result result = SupertrendCalculator.calculate(candles, 10, 3);

        assertThat(result).isNotNull();
        assertThat(result.uptrend()).isTrue();
        assertThat(result.value()).isLessThan(lastClose);
    }

    @Test
    @DisplayName("꾸준히 좁은 변동폭으로 하락하면 하락 추세, 값은 현재가보다 높음(저항선)")
    void calculate_downtrend_abovePrice() {
        List<BinanceKline> candles = trending(500, -1, 40);
        BigDecimal lastClose = candles.get(candles.size() - 1).closePrice();

        SupertrendCalculator.Result result = SupertrendCalculator.calculate(candles, 10, 3);

        assertThat(result).isNotNull();
        assertThat(result.uptrend()).isFalse();
        assertThat(result.value()).isGreaterThan(lastClose);
    }

    /** start에서 시작해 매 캔들 step만큼 종가가 이동하는 좁은 변동폭(고저 폭 1)의 캔들 목록. */
    private static List<BinanceKline> trending(int start, int step, int count) {
        List<BinanceKline> result = new ArrayList<>(count);
        int price = start;
        for (int i = 0; i < count; i++) {
            BigDecimal close = BigDecimal.valueOf(price);
            BigDecimal high = close.add(BigDecimal.ONE);
            BigDecimal low = close.subtract(BigDecimal.ONE);
            result.add(new BinanceKline(
                    i * 60_000L,
                    close, high, low, close,
                    BigDecimal.ONE, i * 60_000L + 59_999L,
                    BigDecimal.ONE, 1L, BigDecimal.ONE, BigDecimal.ONE));
            price += step;
        }
        return result;
    }
}
