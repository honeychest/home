package com.chs.springboot.domain.binance.analysis;

import com.chs.springboot.domain.binance.model.BinanceKline;
import com.chs.springboot.domain.binance.model.BinanceKlineInterval;
import com.chs.springboot.domain.binance.model.IntervalMarketSnapshot;
import com.chs.springboot.domain.binance.model.MarketDataStatus;
import com.chs.springboot.domain.binance.model.MultiTimeframeMarketSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BinanceAnalysisToolsTest {

    @Test
    void candlesOnlyContainConfirmedRecentValues() {
        IntervalMarketSnapshot interval = market(BinanceKlineInterval.ONE_MINUTE, MarketDataStatus.READY);
        BinanceAnalysisTools tools = new BinanceAnalysisTools(snapshot(interval));

        BinanceAnalysisTools.CandleToolResponse result = tools.getCandles("1m", 2);

        assertThat(result.candles()).extracting(BinanceKline::openTimeMs)
                .containsExactly(58 * 60_000L, 59 * 60_000L);
        assertThat(result.candles()).doesNotContain(interval.currentPartial());
    }

    @Test
    void orderFlowCalculatesSellAndNetBaseVolumeFromTakerBuy() {
        IntervalMarketSnapshot interval = market(BinanceKlineInterval.ONE_MINUTE, MarketDataStatus.READY);
        BinanceAnalysisTools tools = new BinanceAnalysisTools(snapshot(interval));

        BinanceAnalysisTools.OrderFlowToolResponse result = tools.getOrderFlow("1m", 1);

        BinanceAnalysisTools.OrderFlowPoint point = result.points().get(0);
        assertThat(point.volume()).isEqualByComparingTo("10");
        assertThat(point.takerBuyBaseVolume()).isEqualByComparingTo("6");
        assertThat(point.sellBaseVolume()).isEqualByComparingTo("4");
        assertThat(point.netBaseVolume()).isEqualByComparingTo("2");
    }

    @Test
    void indicatorHistoryIncludesSeriesAndCrossFlags() {
        IntervalMarketSnapshot interval = market(BinanceKlineInterval.FIVE_MINUTES, MarketDataStatus.READY);
        BinanceAnalysisTools tools = new BinanceAnalysisTools(snapshot(interval));

        BinanceAnalysisTools.IndicatorHistoryToolResponse result = tools.getIndicatorHistory("5m", 5);

        assertThat(result.rsi14().latest()).isNotNull();
        assertThat(result.rsi14().points()).hasSize(5);
        assertThat(result.macd().points()).hasSize(5);
        assertThat(result.supertrend().points()).hasSize(5);
    }

    @Test
    void countAboveLimitIsRejected() {
        BinanceAnalysisTools tools = new BinanceAnalysisTools(snapshot(
                market(BinanceKlineInterval.ONE_MINUTE, MarketDataStatus.READY)));

        assertThatThrownBy(() -> tools.getCandles("1m", 201))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void callbackBudgetStopsSixthToolCall() {
        BinanceAnalysisTools tools = new BinanceAnalysisTools(snapshot(
                market(BinanceKlineInterval.ONE_MINUTE, MarketDataStatus.READY)));
        List<ToolCallback> callbacks = tools.limitedCallbacks(5);
        ToolCallback candles = callbacks.stream()
                .filter(callback -> callback.getToolDefinition().name().equals("getCandles"))
                .findFirst()
                .orElseThrow();

        for (int i = 0; i < 5; i++) {
            assertThat(candles.call("{\"interval\":\"1m\",\"count\":1}")).doesNotContain("TOOL_LIMIT_REACHED");
        }
        assertThat(candles.call("{\"interval\":\"1m\",\"count\":1}"))
                .contains("TOOL_LIMIT_REACHED");
    }

    private static MultiTimeframeMarketSnapshot snapshot(IntervalMarketSnapshot interval) {
        return new MultiTimeframeMarketSnapshot("BTCUSDT", "FUTURES", 1700000000000L,
                true, 1L, List.of(interval), interval.analyzable());
    }

    private static IntervalMarketSnapshot market(BinanceKlineInterval interval, MarketDataStatus status) {
        List<BinanceKline> candles = java.util.stream.IntStream.range(0, 60)
                .mapToObj(index -> candle(interval, index, 100 + index))
                .toList();
        BinanceKline partial = candle(interval, 60, 200);
        return MarketSnapshotCalculator.calculate(interval, candles, partial, 1700000000000L, status, "");
    }

    private static BinanceKline candle(BinanceKlineInterval interval, int index, int close) {
        BigDecimal closePrice = BigDecimal.valueOf(close);
        return new BinanceKline(index * interval.intervalMs(), closePrice, closePrice.add(BigDecimal.ONE),
                closePrice.subtract(BigDecimal.ONE), closePrice, BigDecimal.TEN,
                (index + 1L) * interval.intervalMs() - 1L, BigDecimal.TEN, 1L,
                new BigDecimal("6"), new BigDecimal("6"));
    }
}
