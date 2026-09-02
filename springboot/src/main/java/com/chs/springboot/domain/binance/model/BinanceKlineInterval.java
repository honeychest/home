package com.chs.springboot.domain.binance.model;

import java.util.Arrays;

/** Binance 선물 kline 인터벌과 거래소 시간 간격의 단일 원본. */
public enum BinanceKlineInterval {
    ONE_MINUTE("1m", 60_000L, "kline_1m"),
    FIVE_MINUTES("5m", 5 * 60_000L, "kline_5m"),
    FIFTEEN_MINUTES("15m", 15 * 60_000L, "kline_15m"),
    FOUR_HOURS("4h", 4 * 60 * 60_000L, "kline_4h");

    private final String label;
    private final long intervalMs;
    private final String streamSegment;

    BinanceKlineInterval(String label, long intervalMs, String streamSegment) {
        this.label = label;
        this.intervalMs = intervalMs;
        this.streamSegment = streamSegment;
    }

    public String label() {
        return label;
    }

    public long intervalMs() {
        return intervalMs;
    }

    public String streamSegment() {
        return streamSegment;
    }

    /** 서버 시각에 이미 닫힌 마지막 봉 다음의 배타적 경계. */
    public long latestClosedEndExclusive(long exchangeTimeMs) {
        if (exchangeTimeMs < 0) {
            throw new IllegalArgumentException("거래소 시각은 0 이상이어야 합니다");
        }
        return Math.floorDiv(exchangeTimeMs, intervalMs) * intervalMs;
    }

    public static BinanceKlineInterval fromLabel(String label) {
        return Arrays.stream(values())
                .filter(interval -> interval.label.equals(label))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 Binance kline interval: " + label));
    }
}
