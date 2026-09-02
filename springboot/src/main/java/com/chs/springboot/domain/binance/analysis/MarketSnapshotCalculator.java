package com.chs.springboot.domain.binance.analysis;

import com.chs.springboot.domain.binance.model.BinanceKline;
import com.chs.springboot.domain.binance.model.BinanceKlineInterval;
import com.chs.springboot.domain.binance.model.IntervalMarketSnapshot;
import com.chs.springboot.domain.binance.model.MarketDataStatus;
import com.chs.springboot.domain.binance.service.MacdCalculator;
import com.chs.springboot.domain.binance.service.RsiCalculator;
import com.chs.springboot.domain.binance.service.SupertrendCalculator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/** 라이브 버퍼를 모르는 순수 캔들 계산 계층. 백테스트도 같은 입력 계약을 사용한다. */
public final class MarketSnapshotCalculator {

    private static final int RSI_PERIOD = 14;
    private static final int MACD_FAST = 12;
    private static final int MACD_SLOW = 26;
    private static final int MACD_SIGNAL = 9;
    private static final int SUPERTREND_ATR_PERIOD = 10;
    private static final int SUPERTREND_MULTIPLIER = 3;

    private MarketSnapshotCalculator() {
    }

    public static IntervalMarketSnapshot calculate(
            BinanceKlineInterval interval,
            List<BinanceKline> closedCandles,
            BinanceKline currentPartial,
            long lastReceivedAtMs,
            MarketDataStatus status,
            String statusMessage) {
        Objects.requireNonNull(interval, "interval은 null일 수 없습니다");
        Objects.requireNonNull(status, "시장 데이터 상태는 null일 수 없습니다");
        if (status == MarketDataStatus.READY) {
            KlineSeriesValidator.validate(closedCandles, interval.intervalMs());
        } else {
            validateAvailableValues(closedCandles);
        }
        if (currentPartial != null) {
            KlineSeriesValidator.validate(List.of(currentPartial), interval.intervalMs());
        }

        BinanceKline latestClosed = closedCandles.isEmpty() ? null : closedCandles.get(closedCandles.size() - 1);
        BigDecimal currentPrice = currentPartial != null ? currentPartial.closePrice()
                : latestClosed != null ? latestClosed.closePrice() : null;
        BigDecimal windowHigh = null;
        BigDecimal windowLow = null;
        BigDecimal changePercent = null;
        if (!closedCandles.isEmpty()) {
            windowHigh = closedCandles.get(0).highPrice();
            windowLow = closedCandles.get(0).lowPrice();
            for (BinanceKline candle : closedCandles) {
                windowHigh = windowHigh.max(candle.highPrice());
                windowLow = windowLow.min(candle.lowPrice());
            }
            BigDecimal startPrice = closedCandles.get(0).openPrice();
            if (currentPrice != null && startPrice.signum() != 0) {
                changePercent = currentPrice.subtract(startPrice)
                        .divide(startPrice, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
            }
        }

        MarketIndicators indicators = status == MarketDataStatus.READY
                ? new MarketIndicators(
                RsiCalculator.calculateHistory(closedCandles, RSI_PERIOD, interval.intervalMs(), Integer.MAX_VALUE),
                MacdCalculator.calculateHistory(closedCandles, MACD_FAST, MACD_SLOW, MACD_SIGNAL,
                        interval.intervalMs(), Integer.MAX_VALUE),
                SupertrendCalculator.calculateHistory(closedCandles, SUPERTREND_ATR_PERIOD, SUPERTREND_MULTIPLIER,
                        interval.intervalMs(), Integer.MAX_VALUE))
                : null;

        return new IntervalMarketSnapshot(interval, status, closedCandles, currentPartial, lastReceivedAtMs,
                statusMessage, currentPrice, windowHigh, windowLow, changePercent, indicators);
    }

    private static void validateAvailableValues(List<BinanceKline> closedCandles) {
        for (BinanceKline candle : closedCandles) {
            if (candle == null || candle.openPrice() == null || candle.highPrice() == null
                    || candle.lowPrice() == null || candle.closePrice() == null
                    || candle.volume() == null || candle.takerBuyBaseVolume() == null) {
                throw new IllegalArgumentException("kline 필수 값이 없습니다");
            }
        }
    }
}
