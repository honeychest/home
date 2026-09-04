package com.chs.springboot.domain.binance.analysis;

import com.chs.springboot.domain.binance.model.BinanceKline;
import com.chs.springboot.domain.binance.model.BinanceKlineInterval;
import com.chs.springboot.domain.binance.model.IntervalMarketSnapshot;
import com.chs.springboot.domain.binance.model.MultiTimeframeMarketSnapshot;
import com.chs.springboot.domain.binance.service.MacdCalculator;
import com.chs.springboot.domain.binance.service.RsiCalculator;
import com.chs.springboot.domain.binance.service.SupertrendCalculator;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.support.ToolCallbacks;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** 한 분석 요청에 고정된 읽기 전용 시장 조회 툴 3종. 주문·계정·포지션 권한은 없다. */
public final class BinanceAnalysisTools {

    public static final int MAX_COUNT = 100;

    private final MultiTimeframeMarketSnapshot snapshot;

    public BinanceAnalysisTools(MultiTimeframeMarketSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    @Tool(description = "특정 interval의 최근 확정봉만 조회한다. 진행 중인 봉은 반환하지 않는다.")
    public CandleToolResponse getCandles(
            @ToolParam(description = "1m, 5m, 15m, 4h 또는 1d") String interval,
            @ToolParam(description = "조회할 확정봉 개수, 최대 200") Integer count) {
        int safeCount = validateCount(count);
        IntervalMarketSnapshot market = market(interval);
        List<BinanceKline> candles = tail(market.closedCandles(), safeCount);
        return new CandleToolResponse(market.interval().label(), snapshot.asOfMs(), market.status().name(),
                candles, market.statusMessage());
    }

    @Tool(description = "특정 interval의 RSI, MACD, Supertrend 최신값과 최근 시계열, 교차 정보를 조회한다.")
    public IndicatorHistoryToolResponse getIndicatorHistory(
            @ToolParam(description = "1m, 5m, 15m, 4h 또는 1d") String interval,
            @ToolParam(description = "조회할 시계열 개수, 최대 200") Integer count) {
        int safeCount = validateCount(count);
        IntervalMarketSnapshot market = market(interval);
        MarketIndicators indicators = market.indicators();
        if (indicators == null) {
            return new IndicatorHistoryToolResponse(market.interval().label(), snapshot.asOfMs(),
                    market.status().name(), null, null, null, market.statusMessage());
        }
        RsiCalculator.History rsi = indicators.rsi();
        MacdCalculator.History macd = indicators.macd();
        SupertrendCalculator.History supertrend = indicators.supertrend();
        return new IndicatorHistoryToolResponse(
                market.interval().label(), snapshot.asOfMs(), market.status().name(),
                rsi == null ? null : new RsiHistory(rsi.latest(), tail(rsi.points(), safeCount),
                        rsi.crossedAbove70(), rsi.crossedBelow30()),
                macd == null ? null : new MacdHistory(macd.latest(), tail(macd.points(), safeCount),
                        macd.bullishCross(), macd.bearishCross()),
                supertrend == null ? null : new SupertrendHistory(supertrend.latest(),
                        tail(supertrend.points(), safeCount), supertrend.turnedUp(), supertrend.turnedDown()),
                market.statusMessage());
    }

    @Tool(description = "특정 interval의 최근 확정봉별 taker buy와 나머지 매도량, 순매수량을 조회한다.")
    public OrderFlowToolResponse getOrderFlow(
            @ToolParam(description = "1m, 5m, 15m, 4h 또는 1d") String interval,
            @ToolParam(description = "조회할 확정봉 개수, 최대 200") Integer count) {
        int safeCount = validateCount(count);
        IntervalMarketSnapshot market = market(interval);
        List<OrderFlowPoint> points = tail(market.closedCandles(), safeCount).stream()
                .map(this::toOrderFlowPoint)
                .toList();
        return new OrderFlowToolResponse(market.interval().label(), snapshot.asOfMs(), market.status().name(),
                points, market.statusMessage());
    }

    /** Spring AI 표준 콜백을 요청별로 만들고, 한 요청의 전체 툴 호출 수를 원자적으로 제한한다. */
    public List<ToolCallback> limitedCallbacks(int maxCalls) {
        if (maxCalls <= 0) {
            throw new IllegalArgumentException("툴 호출 상한은 0보다 커야 합니다");
        }
        AtomicInteger callCount = new AtomicInteger();
        return Arrays.stream(ToolCallbacks.from(this))
                .<ToolCallback>map(callback -> new BudgetedToolCallback(callback, callCount, maxCalls))
                .toList();
    }

    private IntervalMarketSnapshot market(String interval) {
        return snapshot.interval(BinanceKlineInterval.fromLabel(interval));
    }

    private int validateCount(Integer count) {
        if (count == null || count <= 0 || count > MAX_COUNT) {
            throw new IllegalArgumentException("조회 count는 1부터 " + MAX_COUNT + "까지입니다");
        }
        return count;
    }

    private <T> List<T> tail(List<T> values, int count) {
        int fromIndex = Math.max(0, values.size() - count);
        return List.copyOf(values.subList(fromIndex, values.size()));
    }

    private OrderFlowPoint toOrderFlowPoint(BinanceKline candle) {
        BigDecimal sell = candle.volume().subtract(candle.takerBuyBaseVolume());
        BigDecimal net = candle.takerBuyBaseVolume().subtract(sell);
        BigDecimal buyRatio = candle.volume().signum() == 0
                ? BigDecimal.ZERO
                : candle.takerBuyBaseVolume().divide(candle.volume(), 6, RoundingMode.HALF_UP);
        return new OrderFlowPoint(candle.openTimeMs(), candle.volume(), candle.takerBuyBaseVolume(), sell, net,
                buyRatio);
    }

    public record CandleToolResponse(String interval, long asOfMs, String status,
                                     List<BinanceKline> candles, String message) {
    }

    public record IndicatorHistoryToolResponse(String interval, long asOfMs, String status,
                                               RsiHistory rsi14, MacdHistory macd,
                                               SupertrendHistory supertrend, String message) {
    }

    public record RsiHistory(BigDecimal latest, List<RsiCalculator.Point> points,
                             boolean crossedAbove70, boolean crossedBelow30) {
    }

    public record MacdHistory(MacdCalculator.Result latest, List<MacdCalculator.Point> points,
                              boolean bullishCross, boolean bearishCross) {
    }

    public record SupertrendHistory(SupertrendCalculator.Result latest,
                                    List<SupertrendCalculator.Point> points,
                                    boolean turnedUp, boolean turnedDown) {
    }

    public record OrderFlowToolResponse(String interval, long asOfMs, String status,
                                        List<OrderFlowPoint> points, String message) {
    }

    public record OrderFlowPoint(long openTimeMs, BigDecimal volume, BigDecimal takerBuyBaseVolume,
                                 BigDecimal sellBaseVolume, BigDecimal netBaseVolume, BigDecimal buyRatio) {
    }

    private static final class BudgetedToolCallback implements ToolCallback {
        private final ToolCallback delegate;
        private final AtomicInteger callCount;
        private final int maxCalls;

        private BudgetedToolCallback(ToolCallback delegate, AtomicInteger callCount, int maxCalls) {
            this.delegate = delegate;
            this.callCount = callCount;
            this.maxCalls = maxCalls;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String arguments) {
            return call(arguments, null);
        }

        @Override
        public String call(String arguments, ToolContext toolContext) {
            if (callCount.incrementAndGet() > maxCalls) {
                return "{\"status\":\"TOOL_LIMIT_REACHED\",\"message\":\"이 요청의 읽기 전용 툴 호출 상한에 도달했습니다. 현재 받은 데이터만으로 답하세요.\"}";
            }
            return delegate.call(arguments, toolContext);
        }
    }
}
