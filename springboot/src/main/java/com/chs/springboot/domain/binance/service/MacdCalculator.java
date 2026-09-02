// [AGENT] MACD(Moving Average Convergence Divergence, 이동평균 수렴확산) — EMA 기반.
// 고정 파라미터(12,26,9), 캘리브레이션 없음 — 확정봉 종가만으로 계산되는 표준 공식.
package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.analysis.KlineSeriesValidator;
import com.chs.springboot.domain.binance.model.BinanceKline;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public final class MacdCalculator {

    private static final int SCALE = 8;

    private MacdCalculator() {
    }

    public record Result(BigDecimal macdLine, BigDecimal signalLine, BigDecimal histogram) {
    }

    public record Point(long openTimeMs, BigDecimal macdLine, BigDecimal signalLine, BigDecimal histogram) {
    }

    public record History(Result latest, List<Point> points,
                          boolean bullishCross, boolean bearishCross) {
        public History {
            points = points == null ? List.of() : List.copyOf(points);
        }
    }

    /** 확정봉 목록(오래된 순)에서 최신 MACD 값을 계산. 데이터가 부족하면 null. */
    public static Result calculate(List<BinanceKline> closedCandles, int fastPeriod, int slowPeriod, int signalPeriod) {
        return calculateHistory(closedCandles, fastPeriod, slowPeriod, signalPeriod,
                60_000L, 1).latest();
    }

    public static Result calculate(List<BinanceKline> closedCandles, int fastPeriod, int slowPeriod,
                                   int signalPeriod, long intervalMs) {
        return calculateHistory(closedCandles, fastPeriod, slowPeriod, signalPeriod,
                intervalMs, 1).latest();
    }

    public static History calculateHistory(List<BinanceKline> closedCandles, int fastPeriod, int slowPeriod,
                                           int signalPeriod, long intervalMs, int maxPoints) {
        validateArguments(closedCandles, fastPeriod, slowPeriod, signalPeriod, intervalMs, maxPoints);
        if (closedCandles.size() < (long) slowPeriod + signalPeriod) {
            return new History(null, List.of(), false, false);
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
            return new History(null, List.of(), false, false);
        }
        List<BigDecimal> signalSeries = ema(macdSeries, signalPeriod);

        List<Point> allPoints = new ArrayList<>(signalSeries.size());
        for (int i = 0; i < signalSeries.size(); i++) {
            int macdIndex = i + signalPeriod - 1;
            BigDecimal macdLine = macdSeries.get(macdIndex).setScale(4, RoundingMode.HALF_UP);
            BigDecimal signalLine = signalSeries.get(i).setScale(4, RoundingMode.HALF_UP);
            allPoints.add(new Point(
                    closedCandles.get(slowPeriod + signalPeriod - 2 + i).openTimeMs(),
                    macdLine,
                    signalLine,
                    macdLine.subtract(signalLine)));
        }

        List<Point> points = tail(allPoints, maxPoints);
        return new History(toResult(allPoints.get(allPoints.size() - 1)), points,
                crossedAboveZero(points), crossedBelowZero(points));
    }

    private static void validateArguments(List<BinanceKline> closedCandles, int fastPeriod, int slowPeriod,
                                          int signalPeriod, long intervalMs, int maxPoints) {
        if (fastPeriod <= 0 || slowPeriod <= 0 || signalPeriod <= 0
                || fastPeriod >= slowPeriod || maxPoints <= 0) {
            throw new IllegalArgumentException("MACD period와 시계열 상한이 올바르지 않습니다");
        }
        KlineSeriesValidator.validate(closedCandles, intervalMs);
    }

    private static Result toResult(Point point) {
        return new Result(point.macdLine(), point.signalLine(), point.histogram());
    }

    private static List<Point> tail(List<Point> points, int maxPoints) {
        int fromIndex = Math.max(0, points.size() - maxPoints);
        return List.copyOf(points.subList(fromIndex, points.size()));
    }

    private static boolean crossedAboveZero(List<Point> points) {
        for (int i = 1; i < points.size(); i++) {
            if (points.get(i - 1).histogram().compareTo(BigDecimal.ZERO) <= 0
                    && points.get(i).histogram().compareTo(BigDecimal.ZERO) > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean crossedBelowZero(List<Point> points) {
        for (int i = 1; i < points.size(); i++) {
            if (points.get(i - 1).histogram().compareTo(BigDecimal.ZERO) >= 0
                    && points.get(i).histogram().compareTo(BigDecimal.ZERO) < 0) {
                return true;
            }
        }
        return false;
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
