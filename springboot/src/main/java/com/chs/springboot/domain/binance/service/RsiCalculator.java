// [AGENT] RSI(Relative Strength Index, 상대강도지수) — Wilder's smoothing 방식.
// 고정 파라미터(기간 14), 캘리브레이션 없음 — 확정봉 종가만으로 계산되는 표준 공식.
package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.BinanceKline;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

class RsiCalculator {

    private static final int SCALE = 8;

    private RsiCalculator() {
    }

    /** 확정봉 목록(오래된 순)에서 최신 RSI 값을 계산. 데이터가 period+1개 미만이면 null. */
    static BigDecimal calculate(List<BinanceKline> closedCandles, int period) {
        if (closedCandles == null || closedCandles.size() < period + 1) {
            return null;
        }

        BigDecimal avgGain = BigDecimal.ZERO;
        BigDecimal avgLoss = BigDecimal.ZERO;

        // 첫 period개 변화량의 단순평균으로 시드
        for (int i = 1; i <= period; i++) {
            BigDecimal change = closedCandles.get(i).closePrice().subtract(closedCandles.get(i - 1).closePrice());
            if (change.signum() > 0) {
                avgGain = avgGain.add(change);
            } else {
                avgLoss = avgLoss.add(change.abs());
            }
        }
        avgGain = avgGain.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
        avgLoss = avgLoss.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);

        // 이후 구간은 Wilder's smoothing으로 이어감(직전 평균에 (period-1) 가중 후 새 값 반영)
        for (int i = period + 1; i < closedCandles.size(); i++) {
            BigDecimal change = closedCandles.get(i).closePrice().subtract(closedCandles.get(i - 1).closePrice());
            BigDecimal gain = change.signum() > 0 ? change : BigDecimal.ZERO;
            BigDecimal loss = change.signum() < 0 ? change.abs() : BigDecimal.ZERO;

            avgGain = avgGain.multiply(BigDecimal.valueOf(period - 1)).add(gain)
                    .divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
            avgLoss = avgLoss.multiply(BigDecimal.valueOf(period - 1)).add(loss)
                    .divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
        }

        if (avgGain.signum() == 0 && avgLoss.signum() == 0) {
            // 가격이 전혀 안 움직인 구간(평탄) — 상승도 하락도 아니므로 중립값
            return BigDecimal.valueOf(50).setScale(2, RoundingMode.HALF_UP);
        }
        if (avgLoss.signum() == 0) {
            return BigDecimal.valueOf(100).setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal rs = avgGain.divide(avgLoss, SCALE, RoundingMode.HALF_UP);
        BigDecimal rsi = BigDecimal.valueOf(100)
                .subtract(BigDecimal.valueOf(100).divide(BigDecimal.ONE.add(rs), SCALE, RoundingMode.HALF_UP));
        return rsi.setScale(2, RoundingMode.HALF_UP);
    }
}
