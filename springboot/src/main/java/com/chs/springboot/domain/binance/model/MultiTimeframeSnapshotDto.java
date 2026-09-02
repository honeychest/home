package com.chs.springboot.domain.binance.model;

import com.chs.springboot.domain.binance.service.MacdCalculator;
import com.chs.springboot.domain.binance.service.RsiCalculator;
import com.chs.springboot.domain.binance.service.SupertrendCalculator;

import java.math.BigDecimal;
import java.util.List;

/** 멀티 인터벌 API 응답. JSON 안정성을 위해 인터벌 데이터를 Map으로 노출하지 않는다. */
public record MultiTimeframeSnapshotDto(
        String symbol,
        String marketType,
        long asOfMs,
        BigDecimal currentPrice,
        List<MarketSnapshotDto> snapshots,
        List<IntervalStatusDto> intervalStatuses,
        boolean analysisAvailable
) {
    public MultiTimeframeSnapshotDto {
        snapshots = snapshots == null ? List.of() : List.copyOf(snapshots);
        intervalStatuses = intervalStatuses == null ? List.of() : List.copyOf(intervalStatuses);
    }

    public static MultiTimeframeSnapshotDto from(MultiTimeframeMarketSnapshot snapshot) {
        List<MarketSnapshotDto> snapshots = snapshot.intervals().stream()
                .map(interval -> toDto(snapshot.symbol(), snapshot.marketType(), interval))
                .toList();
        List<IntervalStatusDto> statuses = snapshot.intervals().stream()
                .map(interval -> new IntervalStatusDto(
                        interval.interval().label(),
                        interval.status().name(),
                        interval.candleCount(),
                        interval.lastReceivedAtMs(),
                        interval.latestClosedOpenTimeMs(),
                        interval.statusMessage()))
                .toList();
        return new MultiTimeframeSnapshotDto(snapshot.symbol(), snapshot.marketType(), snapshot.asOfMs(),
                snapshot.currentPrice(), snapshots, statuses, snapshot.analysisAvailable());
    }

    private static MarketSnapshotDto toDto(String symbol, String marketType, IntervalMarketSnapshot snapshot) {
        RsiCalculator.History rsi = snapshot.indicators() == null ? null : snapshot.indicators().rsi();
        MacdCalculator.History macd = snapshot.indicators() == null ? null : snapshot.indicators().macd();
        SupertrendCalculator.History supertrend = snapshot.indicators() == null
                ? null : snapshot.indicators().supertrend();
        MacdCalculator.Result macdLatest = macd == null ? null : macd.latest();
        SupertrendCalculator.Result supertrendLatest = supertrend == null ? null : supertrend.latest();
        return new MarketSnapshotDto(
                symbol,
                marketType,
                snapshot.interval().label(),
                snapshot.candleCount(),
                snapshot.lastReceivedAtMs(),
                snapshot.currentPrice(),
                snapshot.windowHigh(),
                snapshot.windowLow(),
                snapshot.changePercentFromWindowStart(),
                rsi == null ? null : rsi.latest(),
                macdLatest == null ? null : macdLatest.macdLine(),
                macdLatest == null ? null : macdLatest.signalLine(),
                macdLatest == null ? null : macdLatest.histogram(),
                supertrendLatest == null ? null : supertrendLatest.value(),
                supertrendLatest == null ? null : supertrendLatest.uptrend());
    }
}
