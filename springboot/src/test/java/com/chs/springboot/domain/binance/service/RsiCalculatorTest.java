package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.BinanceKline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RsiCalculatorTest {

    @Test
    @DisplayName("데이터가 period+1개 미만이면 null")
    void calculate_insufficientData_returnsNull() {
        List<BinanceKline> candles = closesOnly(100, 101, 102);

        assertThat(RsiCalculator.calculate(candles, 14)).isNull();
    }

    @Test
    @DisplayName("계속 상승만 하면 RSI는 100(하락이 없어 평균손실 0)")
    void calculate_allGains_returns100() {
        int[] closes = new int[16];
        for (int i = 0; i < closes.length; i++) closes[i] = 100 + i;
        List<BinanceKline> candles = closesOnly(closes);

        BigDecimal rsi = RsiCalculator.calculate(candles, 14);

        assertThat(rsi).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("계속 하락만 하면 RSI는 0(상승이 없어 평균이익 0)")
    void calculate_allLosses_returns0() {
        int[] closes = new int[16];
        for (int i = 0; i < closes.length; i++) closes[i] = 200 - i;
        List<BinanceKline> candles = closesOnly(closes);

        BigDecimal rsi = RsiCalculator.calculate(candles, 14);

        assertThat(rsi).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("가격이 전혀 안 움직이면(평탄) RSI는 50(중립) — 100 아님")
    void calculate_flatPrice_returns50() {
        int[] closes = new int[20];
        java.util.Arrays.fill(closes, 100);
        List<BinanceKline> candles = closesOnly(closes);

        BigDecimal rsi = RsiCalculator.calculate(candles, 14);

        assertThat(rsi).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("등락이 완전히 대칭이면 RSI는 50 근처")
    void calculate_symmetricUpDown_near50() {
        int[] closes = new int[30];
        int price = 100;
        for (int i = 0; i < closes.length; i++) {
            price += (i % 2 == 0) ? 1 : -1;
            closes[i] = price;
        }
        List<BinanceKline> candles = closesOnly(closes);

        BigDecimal rsi = RsiCalculator.calculate(candles, 14);

        assertThat(rsi.doubleValue()).isBetween(40.0, 60.0);
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
