// [AGENT] MACD(Moving Average Convergence Divergence, 이동평균 수렴확산) — EMA 기반.
// 고정 파라미터(12,26,9), 캘리브레이션 없음 — 확정봉 종가만으로 계산되는 표준 공식.
package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.BinanceKline;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

class MacdCalculator {

    private static final int SCALE = 8;

    private MacdCalculator() {
    }

    record Result(BigDecimal macdLine, BigDecimal signalLine, BigDecimal histogram) {
    }

    /** 확정봉 목록(오래된 순)에서 최신 MACD 값을 계산. 데이터가 부족하면 null. */
    static Result calculate(List<BinanceKline> closedCandles, int fastPeriod, int slowPeriod, int signalPeriod) {
        if (closedCandles == null || closedCandles.size() < slowPeriod + signalPeriod) {
            return null;
        }

        List<BigDecimal> closes = closedCandles.stream().map(BinanceKline::closePrice).toList();
        List<BigDecimal> fastEma = ema(closes, fastPeriod);
        List<BigDecimal> slowEma = ema(closes, slowPeriod);

        // fastEma가 slowEma보다 일찍 시작하므로(짧은 기간), 같은 캔들을 가리키도록 앞부분을 잘라 정렬
        int fastStart = fastEma.size() - slowEma.size();
        List<BigDecimal> macdSeries = new ArrayList<>(slowEma.size());
        for (int i = 0; i < slowEma.size(); i++) {
            macdSeries.add(fastEma.get(fastStart + i).subtract(slowEma.get(i)));
        }

        if (macdSeries.size() < signalPeriod) {
            return null;
        }
        List<BigDecimal> signalSeries = ema(macdSeries, signalPeriod);

        // 반올림된 표시값 기준으로 histogram을 계산 — 그래야 화면의 macdLine-signalLine이
        // histogram과 항상 일치한다(Codex 리뷰 지적: 반올림 전 값으로 계산하면 표시값끼리 안 맞을 수 있음).
        BigDecimal macdLine = macdSeries.get(macdSeries.size() - 1).setScale(4, RoundingMode.HALF_UP);
        BigDecimal signalLine = signalSeries.get(signalSeries.size() - 1).setScale(4, RoundingMode.HALF_UP);
        BigDecimal histogram = macdLine.subtract(signalLine);

        return new Result(macdLine, signalLine, histogram);
    }

    /** 지수이동평균 목록 — 첫 period개의 단순평균으로 시드 후 지수평활. 결과 길이 = values.size()-period+1 */
    private static List<BigDecimal> ema(List<BigDecimal> values, int period) {
        BigDecimal k = BigDecimal.valueOf(2).divide(BigDecimal.valueOf(period + 1), SCALE, RoundingMode.HALF_UP);
        BigDecimal oneMinusK = BigDecimal.ONE.subtract(k);

        BigDecimal seed = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) {
            seed = seed.add(values.get(i));
        }
        seed = seed.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);

        List<BigDecimal> result = new ArrayList<>(values.size() - period + 1);
        result.add(seed);
        BigDecimal prev = seed;
        for (int i = period; i < values.size(); i++) {
            BigDecimal current = values.get(i).multiply(k).add(prev.multiply(oneMinusK));
            result.add(current);
            prev = current;
        }
        return result;
    }
}
