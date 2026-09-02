package com.chs.springboot.domain.binance.model;

import java.math.BigDecimal;
import java.util.List;

/** API DTO와 분리된 멀티 타임프레임 내부 스냅샷. 목록 순서는 enum 선언 순서로 고정한다. */
public record MultiTimeframeMarketSnapshot(
        String symbol,
        String marketType,
        long asOfMs,
        boolean leader,
        long leadershipGeneration,
        List<IntervalMarketSnapshot> intervals,
        boolean analysisAvailable
) {
    public MultiTimeframeMarketSnapshot {
        intervals = intervals == null ? List.of() : List.copyOf(intervals);
    }

    public BigDecimal currentPrice() {
        return intervals.stream()
                .map(IntervalMarketSnapshot::currentPrice)
                .filter(price -> price != null)
                .findFirst()
                .orElse(null);
    }

    public IntervalMarketSnapshot interval(BinanceKlineInterval interval) {
        return intervals.stream()
                .filter(snapshot -> snapshot.interval() == interval)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("스냅샷에 없는 interval: " + interval));
    }

    public String overview() {
        StringBuilder result = new StringBuilder();
        result.append("symbol=").append(symbol)
                .append(", market=").append(marketType)
                .append(", asOfMs=").append(asOfMs)
                .append(", currentPrice=").append(currentPrice()).append('\n');
        for (IntervalMarketSnapshot snapshot : intervals) {
            result.append(snapshot.interval().label())
                    .append(" status=").append(snapshot.status())
                    .append(" candles=").append(snapshot.candleCount())
                    .append(" lastClosedOpenTimeMs=").append(snapshot.latestClosedOpenTimeMs())
                    .append(" rsi14=").append(snapshot.indicators() == null || snapshot.indicators().rsi() == null
                            ? null : snapshot.indicators().rsi().latest())
                    .append(" macd=").append(snapshot.indicators() == null || snapshot.indicators().macd() == null
                            || snapshot.indicators().macd().latest() == null ? null
                            : snapshot.indicators().macd().latest().macdLine())
                    .append(" macdSignal=").append(snapshot.indicators() == null || snapshot.indicators().macd() == null
                            || snapshot.indicators().macd().latest() == null ? null
                            : snapshot.indicators().macd().latest().signalLine())
                    .append(" macdHistogram=").append(snapshot.indicators() == null || snapshot.indicators().macd() == null
                            || snapshot.indicators().macd().latest() == null ? null
                            : snapshot.indicators().macd().latest().histogram())
                    .append(" supertrend=").append(snapshot.indicators() == null || snapshot.indicators().supertrend() == null
                            || snapshot.indicators().supertrend().latest() == null ? null
                            : snapshot.indicators().supertrend().latest().value())
                    .append(" uptrend=").append(snapshot.indicators() == null || snapshot.indicators().supertrend() == null
                            || snapshot.indicators().supertrend().latest() == null ? null
                            : snapshot.indicators().supertrend().latest().uptrend())
                    .append('\n');
        }
        return result.toString();
    }
}
