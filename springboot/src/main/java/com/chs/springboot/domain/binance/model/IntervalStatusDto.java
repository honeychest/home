package com.chs.springboot.domain.binance.model;

public record IntervalStatusDto(
        String interval,
        String status,
        int candleCount,
        long lastReceivedAtMs,
        Long latestClosedOpenTimeMs,
        String message
) {
}
