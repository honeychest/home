// [AGENT] Supertrend — ATR(Average True Range, 평균진폭) 기반 추세 추적 지표.
// 고정 파라미터(기간 10, 승수 3), 캘리브레이션 없음 — 확정봉 고가/저가/종가만으로 계산.
package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.analysis.KlineSeriesValidator;
import com.chs.springboot.domain.binance.model.BinanceKline;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public final class SupertrendCalculator {

    private static final int SCALE = 8;

    private SupertrendCalculator() {
    }

    /** value: 현재 지지(상승 추세)/저항(하락 추세)선 값. uptrend: 상승 추세 여부. */
    public record Result(BigDecimal value, boolean uptrend) {
    }

    public record Point(long openTimeMs, BigDecimal value, boolean uptrend) {
    }

    public record History(Result latest, List<Point> points,
                          boolean turnedUp, boolean turnedDown) {
        public History {
            points = points == null ? List.of() : List.copyOf(points);
        }
    }

    /** 확정봉 목록(오래된 순)에서 최신 Supertrend 값을 계산. 데이터가 부족하면 null. */
    public static Result calculate(List<BinanceKline> closedCandles, int atrPeriod, int multiplier) {
        return calculateHistory(closedCandles, atrPeriod, multiplier, 60_000L, 1).latest();
    }

    public static Result calculate(List<BinanceKline> closedCandles, int atrPeriod, int multiplier,
                                   long intervalMs) {
        return calculateHistory(closedCandles, atrPeriod, multiplier, intervalMs, 1).latest();
    }

    public static History calculateHistory(List<BinanceKline> closedCandles, int atrPeriod, int multiplier,
                                           long intervalMs, int maxPoints) {
        validateArguments(closedCandles, atrPeriod, multiplier, intervalMs, maxPoints);
        if (closedCandles.size() <= atrPeriod) {
            return new History(null, List.of(), false, false);
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
        List<Point> allPoints = new ArrayList<>(n - atrPeriod + 1);
        boolean turnedUp = false;
        boolean turnedDown = false;

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
                allPoints.add(point(closedCandles, i, finalLower, finalUpper, uptrend));
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
                turnedDown = true;
            } else if (!uptrend && k.closePrice().compareTo(newUpper) > 0) {
                uptrend = true;
                turnedUp = true;
            }

            finalUpper = newUpper;
            finalLower = newLower;
            allPoints.add(point(closedCandles, i, finalLower, finalUpper, uptrend));
        }

        List<Point> points = tail(allPoints, maxPoints);
        Point latest = allPoints.get(allPoints.size() - 1);
        return new History(new Result(latest.value(), latest.uptrend()), points, turnedUp, turnedDown);
    }

    private static void validateArguments(List<BinanceKline> closedCandles, int atrPeriod, int multiplier,
                                          long intervalMs, int maxPoints) {
        if (atrPeriod <= 0 || multiplier <= 0 || maxPoints <= 0) {
            throw new IllegalArgumentException("Supertrend period와 승수, 시계열 상한이 올바르지 않습니다");
        }
        KlineSeriesValidator.validate(closedCandles, intervalMs);
    }

    private static Point point(List<BinanceKline> candles, int index,
                              BigDecimal finalLower, BigDecimal finalUpper, boolean uptrend) {
        return new Point(candles.get(index).openTimeMs(),
                (uptrend ? finalLower : finalUpper).setScale(2, RoundingMode.HALF_UP), uptrend);
    }

    private static List<Point> tail(List<Point> points, int maxPoints) {
        int fromIndex = Math.max(0, points.size() - maxPoints);
        return List.copyOf(points.subList(fromIndex, points.size()));
    }
}
