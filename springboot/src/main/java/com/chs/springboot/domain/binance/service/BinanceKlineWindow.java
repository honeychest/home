package com.chs.springboot.domain.binance.service;

/**
 * Binance klines 요청에 사용하는 시작 시각과 완료 봉 끝의 배타적 시각.
 * endTime 요청 파라미터는 API의 inclusive 규칙에 맞춰 호출부에서 endTimeMsExclusive - 1로 변환한다.
 */
public record BinanceKlineWindow(long startTimeMs, long endTimeMsExclusive) {

    public static final long INTERVAL_MS = 60_000L;
    public static final long HOUR_MS = 3_600_000L;
    public static final long DEFAULT_SEED_HOURS = 48L;
    public static final long SAFE_DELAY_MS = 2L * INTERVAL_MS;

    public boolean isEmpty() {
        return startTimeMs >= endTimeMsExclusive;
    }

    public static BinanceKlineWindow fromLastCandle(Long lastCandleTimeMs, long nowMs) {
        return fromLastCandle(lastCandleTimeMs, nowMs, DEFAULT_SEED_HOURS);
    }

    public static BinanceKlineWindow fromLastCandle(Long lastCandleTimeMs, long nowMs, long seedHours) {
        long currentCandleStartMs = safeEnd(nowMs);
        long startTimeMs;
        if (lastCandleTimeMs == null) {
            startTimeMs = Math.max(0L, currentCandleStartMs - Math.multiplyExact(seedHours, HOUR_MS));
        } else {
            startTimeMs = Math.addExact(lastCandleTimeMs, INTERVAL_MS);
        }
        return new BinanceKlineWindow(startTimeMs, currentCandleStartMs);
    }

    public static long safeEnd(long nowMs) {
        return Math.floorDiv(nowMs, INTERVAL_MS) * INTERVAL_MS - SAFE_DELAY_MS;
    }

    public long nextPageStart(long lastReturnedOpenTimeMs) {
        long next = Math.addExact(lastReturnedOpenTimeMs, INTERVAL_MS);
        if (next <= startTimeMs) {
            throw new IllegalArgumentException("Binance kline 응답이 시간 범위를 전진시키지 않았습니다");
        }
        return next;
    }
}
