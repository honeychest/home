package com.chs.springboot.domain.binance.service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 시그널·차트 소비자가 공유하는 읽기 전용 캔들 원천 계약.
 * 원천별 시장 행과 legacy/temp 경계는 구현체가 숨기고, 호출부에는 합성된 캔들만 노출한다.
 */
public interface SignalCandleSource {

    enum Interval {
        ONE_MINUTE("1m", 60_000L),
        FIVE_MINUTES("5m", 300_000L),
        FIFTEEN_MINUTES("15m", 900_000L);

        private final String value;
        private final long durationMs;

        Interval(String value, long durationMs) {
            this.value = value;
            this.durationMs = durationMs;
        }

        public String value() {
            return value;
        }

        public long durationMs() {
            return durationMs;
        }

        public static Interval from(String value) {
            for (Interval interval : values()) {
                if (interval.value.equals(value)) {
                    return interval;
                }
            }
            throw new IllegalArgumentException("지원하지 않는 캔들 간격: " + value);
        }
    }

    enum QueryMode {
        COMPLETED,
        IN_PROGRESS
    }

    /** 합성된 캔들. timeMs는 UTC 기준 봉 시작 시각이다. */
    record SignalCandle(
            String symbol,
            long timeMs,
            BigDecimal openPrice,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            BigDecimal closePrice,
            BigDecimal quoteVolume,
            BigDecimal baseVolume,
            BigDecimal delta) {
    }

    /** 기존 /history 계약의 long/short 에너지 합계를 원천 안에서 계산한 결과. */
    record Energy(BigDecimal longEnergy, BigDecimal shortEnergy) {
    }

    List<SignalCandle> find(
            String symbol,
            Interval interval,
            long fromMs,
            long toMsExclusive,
            QueryMode mode);

    List<SignalCandle> findBefore(
            String symbol,
            Interval interval,
            long beforeMs,
            int limit,
            QueryMode mode);

    List<SignalCandle> findByQuoteVolume(
            String symbol,
            Interval interval,
            BigDecimal minQuoteVolume,
            BigDecimal maxQuoteVolume,
            QueryMode mode);

    List<String> findCandleDates(String symbol);

    Energy sumEnergy(
            String symbol,
            Interval interval,
            long fromMs,
            long toMsExclusive,
            QueryMode mode);
}
