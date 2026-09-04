package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.BinanceKlineInterval;

/**
 * Binance klines 요청에 사용하는 시작 시각과 완료 봉 끝의 배타적 시각.
 * endTime 요청 파라미터는 API의 inclusive 규칙에 맞춰 호출부에서 endTimeMsExclusive - 1로 변환한다.
 * interval을 받지 않는 메서드는 기존 호출부 호환을 위해 1분으로 고정된다.
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
        return fromLastCandle(lastCandleTimeMs, nowMs, DEFAULT_SEED_HOURS, BinanceKlineInterval.ONE_MINUTE);
    }

    public static BinanceKlineWindow fromLastCandle(Long lastCandleTimeMs, long nowMs, long seedHours) {
        return fromLastCandle(lastCandleTimeMs, nowMs, seedHours, BinanceKlineInterval.ONE_MINUTE);
    }

    public static BinanceKlineWindow fromLastCandle(
            Long lastCandleTimeMs, long nowMs, long seedHours, BinanceKlineInterval interval) {
        long currentCandleStartMs = safeEnd(nowMs, interval);
        long startTimeMs;
        if (lastCandleTimeMs == null) {
            startTimeMs = Math.max(0L, currentCandleStartMs - Math.multiplyExact(seedHours, HOUR_MS));
        } else {
            startTimeMs = Math.addExact(lastCandleTimeMs, interval.intervalMs());
        }
        return new BinanceKlineWindow(startTimeMs, currentCandleStartMs);
    }

    public static long safeEnd(long nowMs) {
        return safeEnd(nowMs, BinanceKlineInterval.ONE_MINUTE);
    }

    /** 안전 지연은 인터벌의 2배(1분이면 2분, 5분이면 10분)로 스케일된다. */
    public static long safeEnd(long nowMs, BinanceKlineInterval interval) {
        long intervalMs = interval.intervalMs();
        long safeDelayMs = 2L * intervalMs;
        return Math.floorDiv(nowMs, intervalMs) * intervalMs - safeDelayMs;
    }

    public long nextPageStart(long lastReturnedOpenTimeMs) {
        return nextPageStart(lastReturnedOpenTimeMs, BinanceKlineInterval.ONE_MINUTE);
    }

    public long nextPageStart(long lastReturnedOpenTimeMs, BinanceKlineInterval interval) {
        long next = Math.addExact(lastReturnedOpenTimeMs, interval.intervalMs());
        if (next <= startTimeMs) {
            throw new IllegalArgumentException("Binance kline 응답이 시간 범위를 전진시키지 않았습니다");
        }
        return next;
    }
}
