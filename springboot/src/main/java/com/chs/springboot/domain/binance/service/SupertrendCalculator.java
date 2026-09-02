// [AGENT] Supertrend — ATR(Average True Range, 평균진폭) 기반 추세 추적 지표.
// 고정 파라미터(기간 10, 승수 3), 캘리브레이션 없음 — 확정봉 고가/저가/종가만으로 계산.
package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.BinanceKline;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

class SupertrendCalculator {

    private static final int SCALE = 8;

    private SupertrendCalculator() {
    }

    /** value: 현재 지지(상승 추세)/저항(하락 추세)선 값. uptrend: 상승 추세 여부. */
    record Result(BigDecimal value, boolean uptrend) {
    }

    /** 확정봉 목록(오래된 순)에서 최신 Supertrend 값을 계산. 데이터가 부족하면 null. */
    static Result calculate(List<BinanceKline> closedCandles, int atrPeriod, int multiplier) {
        if (closedCandles == null || closedCandles.size() < atrPeriod + 1) {
            return null;
        }

        int n = closedCandles.size();
        BigDecimal[] tr = new BigDecimal[n];
        for (int i = 0; i < n; i++) {
            BinanceKline k = closedCandles.get(i);
            BigDecimal highLow = k.highPrice().subtract(k.lowPrice());
            if (i == 0) {
                tr[i] = highLow;
            } else {
                BigDecimal prevClose = closedCandles.get(i - 1).closePrice();
                BigDecimal highClose = k.highPrice().subtract(prevClose).abs();
                BigDecimal lowClose = k.lowPrice().subtract(prevClose).abs();
                tr[i] = highLow.max(highClose).max(lowClose);
            }
        }

        // ATR: Wilder's smoothing, 첫 atrPeriod개는 단순평균으로 시드
        BigDecimal[] atr = new BigDecimal[n];
        BigDecimal seed = BigDecimal.ZERO;
        for (int i = 0; i < atrPeriod; i++) {
            seed = seed.add(tr[i]);
        }
        atr[atrPeriod - 1] = seed.divide(BigDecimal.valueOf(atrPeriod), SCALE, RoundingMode.HALF_UP);
        for (int i = atrPeriod; i < n; i++) {
            atr[i] = atr[i - 1].multiply(BigDecimal.valueOf(atrPeriod - 1)).add(tr[i])
                    .divide(BigDecimal.valueOf(atrPeriod), SCALE, RoundingMode.HALF_UP);
        }

        BigDecimal mult = BigDecimal.valueOf(multiplier);
        BigDecimal finalUpper = null;
        BigDecimal finalLower = null;
        boolean uptrend = true;

        for (int i = atrPeriod - 1; i < n; i++) {
            BinanceKline k = closedCandles.get(i);
            BigDecimal mid = k.highPrice().add(k.lowPrice())
                    .divide(BigDecimal.valueOf(2), SCALE, RoundingMode.HALF_UP);
            BigDecimal basicUpper = mid.add(mult.multiply(atr[i]));
            BigDecimal basicLower = mid.subtract(mult.multiply(atr[i]));

            if (finalUpper == null) {
                finalUpper = basicUpper;
                finalLower = basicLower;
                uptrend = k.closePrice().compareTo(basicLower) > 0;
                continue;
            }

            BigDecimal prevClose = closedCandles.get(i - 1).closePrice();
            // 밴드는 가격 쪽으로만 좁혀지고, 반대로는 안 움직인다(표준 Supertrend 규칙)
            BigDecimal newUpper = (basicUpper.compareTo(finalUpper) < 0 || prevClose.compareTo(finalUpper) > 0)
                    ? basicUpper : finalUpper;
            BigDecimal newLower = (basicLower.compareTo(finalLower) > 0 || prevClose.compareTo(finalLower) < 0)
                    ? basicLower : finalLower;

            if (uptrend && k.closePrice().compareTo(newLower) < 0) {
                uptrend = false;
            } else if (!uptrend && k.closePrice().compareTo(newUpper) > 0) {
                uptrend = true;
            }

            finalUpper = newUpper;
            finalLower = newLower;
        }

        BigDecimal value = (uptrend ? finalLower : finalUpper).setScale(2, RoundingMode.HALF_UP);
        return new Result(value, uptrend);
    }
}
