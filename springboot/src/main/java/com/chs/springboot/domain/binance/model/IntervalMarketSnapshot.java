package com.chs.springboot.domain.binance.model;

import com.chs.springboot.domain.binance.analysis.MarketIndicators;

import java.math.BigDecimal;
import java.util.List;

/** 한 인터벌의 확정봉·현재가·지표를 함께 고정한 불변 스냅샷. */
public record IntervalMarketSnapshot(
        BinanceKlineInterval interval,
        MarketDataStatus status,
        List<BinanceKline> closedCandles,
        BinanceKline currentPartial,
        long lastReceivedAtMs,
        String statusMessage,
        BigDecimal currentPrice,
        BigDecimal windowHigh,
        BigDecimal windowLow,
        BigDecimal changePercentFromWindowStart,
        MarketIndicators indicators
) {
    public IntervalMarketSnapshot {
        closedCandles = closedCandles == null ? List.of() : List.copyOf(closedCandles);
        statusMessage = statusMessage == null ? "" : statusMessage;
    }

    public int candleCount() {
        return closedCandles.size();
    }

    public Long latestClosedOpenTimeMs() {
        return closedCandles.isEmpty() ? null : closedCandles.get(closedCandles.size() - 1).openTimeMs();
    }

    public boolean analyzable() {
        return status == MarketDataStatus.READY && currentPrice != null
                && indicators != null && indicators.hasLatestValues();
    }
}
