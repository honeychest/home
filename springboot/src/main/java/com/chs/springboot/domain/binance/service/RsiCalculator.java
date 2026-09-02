// [AGENT] RSI(Relative Strength Index, 상대강도지수) — Wilder's smoothing 방식.
// 고정 파라미터(기간 14), 캘리브레이션 없음 — 확정봉 종가만으로 계산되는 표준 공식.
package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.analysis.KlineSeriesValidator;
import com.chs.springboot.domain.binance.model.BinanceKline;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public final class RsiCalculator {

    private static final int SCALE = 8;

    public record Point(long openTimeMs, BigDecimal value) {
    }

    public record History(BigDecimal latest, List<Point> points,
                          boolean crossedAbove70, boolean crossedBelow30) {
        public History {
            points = points == null ? List.of() : List.copyOf(points);
        }
    }

    private RsiCalculator() {
    }

    /** 확정봉 목록(오래된 순)에서 최신 RSI 값을 계산. 데이터가 period+1개 미만이면 null. */
    public static BigDecimal calculate(List<BinanceKline> closedCandles, int period) {
        return calculateHistory(closedCandles, period, 60_000L, 1).latest();
    }

    public static BigDecimal calculate(List<BinanceKline> closedCandles, int period, long intervalMs) {
        return calculateHistory(closedCandles, period, intervalMs, 1).latest();
    }

    public static History calculateHistory(List<BinanceKline> closedCandles, int period,
                                           long intervalMs, int maxPoints) {
        validateArguments(closedCandles, period, intervalMs, maxPoints);
        if (closedCandles.size() <= period) {
            return new History(null, List.of(), false, false);
        }

        List<Point> allPoints = new ArrayList<>(closedCandles.size() - period);
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
        allPoints.add(new Point(closedCandles.get(period).openTimeMs(), value(avgGain, avgLoss)));

        // 이후 구간은 Wilder's smoothing으로 이어감(직전 평균에 (period-1) 가중 후 새 값 반영)
        for (int i = period + 1; i < closedCandles.size(); i++) {
            BigDecimal change = closedCandles.get(i).closePrice().subtract(closedCandles.get(i - 1).closePrice());
            BigDecimal gain = change.signum() > 0 ? change : BigDecimal.ZERO;
            BigDecimal loss = change.signum() < 0 ? change.abs() : BigDecimal.ZERO;

            avgGain = avgGain.multiply(BigDecimal.valueOf(period - 1)).add(gain)
                    .divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
            avgLoss = avgLoss.multiply(BigDecimal.valueOf(period - 1)).add(loss)
                    .divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
            allPoints.add(new Point(closedCandles.get(i).openTimeMs(), value(avgGain, avgLoss)));
        }

        List<Point> points = tail(allPoints, maxPoints);
        boolean crossedAbove70 = crossedAbove(points, 70);
        boolean crossedBelow30 = crossedBelow(points, 30);
        return new History(allPoints.get(allPoints.size() - 1).value(), points,
                crossedAbove70, crossedBelow30);
    }

    private static void validateArguments(List<BinanceKline> closedCandles, int period,
                                          long intervalMs, int maxPoints) {
        if (period <= 0 || maxPoints <= 0) {
            throw new IllegalArgumentException("RSI period와 시계열 상한은 0보다 커야 합니다");
        }
        KlineSeriesValidator.validate(closedCandles, intervalMs);
    }

    private static BigDecimal value(BigDecimal avgGain, BigDecimal avgLoss) {
        if (avgGain.signum() == 0 && avgLoss.signum() == 0) {
            return BigDecimal.valueOf(50).setScale(2, RoundingMode.HALF_UP);
        }
        if (avgLoss.signum() == 0) {
            return BigDecimal.valueOf(100).setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal rs = avgGain.divide(avgLoss, SCALE, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(100)
                .subtract(BigDecimal.valueOf(100).divide(BigDecimal.ONE.add(rs), SCALE, RoundingMode.HALF_UP))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static List<Point> tail(List<Point> points, int maxPoints) {
        int fromIndex = Math.max(0, points.size() - maxPoints);
        return List.copyOf(points.subList(fromIndex, points.size()));
    }

    private static boolean crossedAbove(List<Point> points, int threshold) {
        for (int i = 1; i < points.size(); i++) {
            if (points.get(i - 1).value().compareTo(BigDecimal.valueOf(threshold)) <= 0
                    && points.get(i).value().compareTo(BigDecimal.valueOf(threshold)) > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean crossedBelow(List<Point> points, int threshold) {
        for (int i = 1; i < points.size(); i++) {
            if (points.get(i - 1).value().compareTo(BigDecimal.valueOf(threshold)) >= 0
                    && points.get(i).value().compareTo(BigDecimal.valueOf(threshold)) < 0) {
                return true;
            }
        }
        return false;
    }
}
