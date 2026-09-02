// [AGENT] 자동매매 현황분석 스냅샷 응답 DTO. AutoTradeAdminController가 반환.
package com.chs.springboot.domain.binance.model;

import java.math.BigDecimal;

public record MarketSnapshotDto(
        String symbol,
        String marketType,
        String interval,
        int candleCount,
        long lastUpdatedMs,
        BigDecimal currentPrice,
        BigDecimal windowHigh,
        BigDecimal windowLow,
        BigDecimal changePercentFromWindowStart,
        BigDecimal rsi14,
        BigDecimal macdLine,
        BigDecimal macdSignal,
        BigDecimal macdHistogram,
        BigDecimal supertrendValue,
        Boolean supertrendUptrend
) {
}
