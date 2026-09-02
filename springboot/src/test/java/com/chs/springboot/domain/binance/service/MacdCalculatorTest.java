package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.BinanceKline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MacdCalculatorTest {

    @Test
    @DisplayName("데이터가 부족하면 null")
    void calculate_insufficientData_returnsNull() {
        List<BinanceKline> candles = closesOnly(range(1, 10));

        assertThat(MacdCalculator.calculate(candles, 12, 26, 9)).isNull();
    }

    @Test
    @DisplayName("꾸준히 상승하면 MACD 라인은 양수(단기 EMA가 장기 EMA 위)")
    void calculate_uptrend_positiveMacd() {
        int[] closes = new int[60];
        for (int i = 0; i < closes.length; i++) closes[i] = 100 + i * 2;
        List<BinanceKline> candles = closesOnly(closes);

        MacdCalculator.Result result = MacdCalculator.calculate(candles, 12, 26, 9);

        assertThat(result).isNotNull();
        assertThat(result.macdLine()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("꾸준히 하락하면 MACD 라인은 음수")
    void calculate_downtrend_negativeMacd() {
        int[] closes = new int[60];
        for (int i = 0; i < closes.length; i++) closes[i] = 500 - i * 2;
        List<BinanceKline> candles = closesOnly(closes);

        MacdCalculator.Result result = MacdCalculator.calculate(candles, 12, 26, 9);

        assertThat(result).isNotNull();
        assertThat(result.macdLine()).isLessThan(BigDecimal.ZERO);
        assertThat(result.histogram()).isEqualByComparingTo(
                result.macdLine().subtract(result.signalLine()));
    }

    private static int[] range(int from, int toExclusive) {
        int[] result = new int[toExclusive - from];
        for (int i = 0; i < result.length; i++) result[i] = from + i;
        return result;
    }

    private static List<BinanceKline> closesOnly(int... closes) {
        List<BinanceKline> result = new ArrayList<>(closes.length);
        for (int i = 0; i < closes.length; i++) {
            BigDecimal close = BigDecimal.valueOf(closes[i]);
            result.add(new BinanceKline(
                    i * 60_000L,
                    close, close, close, close,
                    BigDecimal.ONE, i * 60_000L + 59_999L,
                    BigDecimal.ONE, 1L, BigDecimal.ONE, BigDecimal.ONE));
        }
        return result;
    }
}
