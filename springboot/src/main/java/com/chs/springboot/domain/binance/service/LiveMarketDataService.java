package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.analysis.KlineSeriesValidator;
import com.chs.springboot.domain.binance.analysis.MarketSnapshotCalculator;
import com.chs.springboot.domain.binance.model.BinanceKline;
import com.chs.springboot.domain.binance.model.BinanceKlineInterval;
import com.chs.springboot.domain.binance.model.IntervalMarketSnapshot;
import com.chs.springboot.domain.binance.model.MarketDataStatus;
import com.chs.springboot.domain.binance.model.MarketSnapshotDto;
import com.chs.springboot.domain.binance.model.MultiTimeframeMarketSnapshot;
import com.chs.springboot.global.redis.LeadershipChangedEvent;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class LiveMarketDataService {

    private static final Logger log = LoggerFactory.getLogger(LiveMarketDataService.class);

    static final String SYMBOL = "BTCUSDT";
    static final String MARKET_TYPE = "FUTURES";
    static final int BUFFER_MAX_SIZE = 1_000;
    static final int SEED_COUNT = 1_000;
    private static final String FUTURES_WS_BASE = "wss://fstream.binance.com/market/ws/";
    private static final long STALE_THRESHOLD_MS = 3 * 60_000L;
    private static final List<BinanceKlineInterval> INTERVALS = List.of(BinanceKlineInterval.values());

    private final BinanceKlineRestClient restClient;
    private final KlineStreamEventParser eventParser;
    private final StreamFactory streamFactory;
    private final Clock clock;
    private final Map<BinanceKlineInterval, IntervalRuntime> runtimes;

    private final ScheduledExecutorService streamScheduler =
            Executors.newScheduledThreadPool(4, runnable -> {
                Thread thread = new Thread(runnable, "live-market-data-stream");
                thread.setDaemon(true);
                return thread;
            });
    private final ExecutorService backfillExecutor =
            Executors.newFixedThreadPool(4, runnable -> {
                Thread thread = new Thread(runnable, "live-market-data-backfill");
                thread.setDaemon(true);
                return thread;
            });
    private final Object leadershipLock = new Object();
    private final AtomicInteger generation = new AtomicInteger(0);
    private volatile boolean leader;
    private volatile long leadershipEpoch;
    private volatile String leadershipOwnerToken = "";

    @Autowired
    public LiveMarketDataService(BinanceKlineRestClient restClient, KlineStreamEventParser eventParser) {
        this(restClient, eventParser, BinanceWebSocketStream::new, Clock.systemUTC());
    }

    LiveMarketDataService(BinanceKlineRestClient restClient, KlineStreamEventParser eventParser,
                          StreamFactory streamFactory, Clock clock) {
        this.restClient = restClient;
        this.eventParser = eventParser;
        this.streamFactory = streamFactory;
        this.clock = clock;
        EnumMap<BinanceKlineInterval, IntervalRuntime> runtimeMap = new EnumMap<>(BinanceKlineInterval.class);
        for (BinanceKlineInterval interval : INTERVALS) {
            runtimeMap.put(interval, new IntervalRuntime(interval));
        }
        this.runtimes = Map.copyOf(runtimeMap);
    }

    @EventListener
    public void onLeadershipChanged(LeadershipChangedEvent event) {
        int myGeneration;
        synchronized (leadershipLock) {
            if (event.epoch() < leadershipEpoch) {
                return;
            }
            if (isDuplicateLeadershipEvent(event)) {
                return;
            }
            myGeneration = generation.incrementAndGet();
            leader = event.leader();
            leadershipEpoch = event.epoch();
            leadershipOwnerToken = Objects.requireNonNullElse(event.ownerToken(), "");
            if (!leader) {
                for (IntervalRuntime runtime : runtimes.values()) {
                    runtime.status = MarketDataStatus.ERROR;
                    runtime.statusMessage = "리더 노드가 아니어서 수집을 중단했습니다";
                    runtime.connectedOnce.set(false);
                    stopStream(runtime);
                }
                return;
            }
            for (IntervalRuntime runtime : runtimes.values()) {
                runtime.status = MarketDataStatus.BACKFILLING;
                runtime.statusMessage = "선물 확정봉 초기 적재 중입니다";
                runtime.connectedOnce.set(false);
                runtime.reconcilePending.set(false);
                stopStream(runtime);
                backfillExecutor.execute(() -> startForGeneration(runtime, myGeneration));
            }
        }
    }

    private boolean isDuplicateLeadershipEvent(LeadershipChangedEvent event) {
        String ownerToken = Objects.requireNonNullElse(event.ownerToken(), "");
        if (event.leader()) {
            return leader && leadershipEpoch == event.epoch() && leadershipOwnerToken.equals(ownerToken);
        }
        return !leader && leadershipEpoch == event.epoch() && leadershipOwnerToken.equals(ownerToken);
    }

    public boolean isLeader() {
        return leader;
    }

    public long leadershipGeneration() {
        return generation.get();
    }

    /** 기존 관리자 테스트 호출부와의 호환용 1분봉 변환. 신규 API는 buildSnapshot()을 사용한다. */
    public MarketSnapshotDto buildSnapshotDto() {
        return toDto(runtimes.get(BinanceKlineInterval.ONE_MINUTE).buffer.snapshot(),
                SYMBOL, MARKET_TYPE, BinanceKlineInterval.ONE_MINUTE.label());
    }

    public MultiTimeframeMarketSnapshot buildSnapshot() {
        long asOfMs = clock.millis();
        List<IntervalMarketSnapshot> snapshots = new ArrayList<>(INTERVALS.size());
        for (BinanceKlineInterval interval : INTERVALS) {
            IntervalRuntime runtime = runtimes.get(interval);
            refreshStale(runtime, asOfMs);
            LiveKlineBuffer.Snapshot bufferSnapshot = runtime.buffer.snapshot();
            MarketDataStatus status = runtime.status;
            if (status == MarketDataStatus.READY && KlineSeriesValidator.hasGap(
                    bufferSnapshot.closedCandles(), interval.intervalMs())) {
                runtime.status = MarketDataStatus.GAP;
                runtime.statusMessage = "확정봉 사이 결측이 감지되어 보정 중입니다";
                scheduleReconcile(runtime, generation.get());
                status = MarketDataStatus.GAP;
            }
            snapshots.add(MarketSnapshotCalculator.calculate(interval, bufferSnapshot.closedCandles(),
                    bufferSnapshot.currentPartial(), bufferSnapshot.lastAcceptedAtMs(), status,
                    runtime.statusMessage));
        }
        boolean analysisAvailable = leader && snapshots.stream().allMatch(IntervalMarketSnapshot::analyzable);
        return new MultiTimeframeMarketSnapshot(SYMBOL, MARKET_TYPE, asOfMs, leader, generation.get(),
                snapshots, analysisAvailable);
    }

    public boolean isStale() {
        IntervalRuntime runtime = runtimes.get(BinanceKlineInterval.ONE_MINUTE);
        refreshStale(runtime, clock.millis());
        return runtime.status == MarketDataStatus.STALE;
    }

    private void refreshStale(IntervalRuntime runtime, long nowMs) {
        if (runtime.status == MarketDataStatus.READY
                && runtime.buffer.snapshot().lastAcceptedAtMs() > 0
                && nowMs - runtime.buffer.snapshot().lastAcceptedAtMs() > STALE_THRESHOLD_MS) {
            runtime.status = MarketDataStatus.STALE;
            runtime.statusMessage = "웹소켓 메시지가 오래 수신되지 않았습니다";
        }
    }

    private void startForGeneration(IntervalRuntime runtime, int myGeneration) {
        if (!isCurrentGeneration(myGeneration)) {
            return;
        }
        try {
            List<BinanceKline> seed = restClient.fetchLatestClosedFutures(SYMBOL, runtime.interval, SEED_COUNT);
            if (seed.isEmpty()) {
                throw new IllegalStateException("선물 확정봉 응답이 비어 있습니다");
            }
            if (!isCurrentGeneration(myGeneration)) {
                log.info("[LiveMarketData] 초기 적재 결과 폐기 interval={} gen={}", runtime.interval.label(), myGeneration);
                return;
            }
            runtime.buffer.seed(seed);
            runtime.status = MarketDataStatus.CONNECTING;
            runtime.statusMessage = "웹소켓 연결 중입니다";
            connectStream(runtime, myGeneration);
        } catch (Exception e) {
            if (isCurrentGeneration(myGeneration)) {
                runtime.status = MarketDataStatus.ERROR;
                runtime.statusMessage = "초기 적재 실패: " + safeMessage(e);
                log.warn("[LiveMarketData] 초기 적재 실패 interval={} gen={}: {}",
                        runtime.interval.label(), myGeneration, e.getMessage());
            }
        }
    }

    private void connectStream(IntervalRuntime runtime, int myGeneration) {
        String streamName = "btcusdt@" + runtime.interval.streamSegment();
        String url = FUTURES_WS_BASE + streamName;
        BinanceWebSocketStream stream = streamFactory.create(url, "LiveMarketData/" + runtime.interval.label(),
                json -> onMessage(runtime, myGeneration, json), streamScheduler, 5);
        stream.onError(error -> onStreamError(runtime, myGeneration, error));
        stream.onConnected(ignored -> onStreamConnected(runtime, myGeneration));

        boolean discard;
        synchronized (runtime) {
            discard = !isCurrentGeneration(myGeneration);
            if (!discard) {
                runtime.activeStream = stream;
                // 최종 검사와 connect를 같은 잠금 안에서 실행해 리더 해제 직후의 TOCTOU를 막는다.
                stream.connect();
            }
        }
        if (discard) {
            stream.disconnect();
        }
    }

    private void onStreamConnected(IntervalRuntime runtime, int myGeneration) {
        if (!isCurrentGeneration(myGeneration)) {
            return;
        }
        if (runtime.connectedOnce.compareAndSet(false, true)) {
            runtime.status = MarketDataStatus.READY;
            runtime.statusMessage = "";
            return;
        }
        runtime.status = MarketDataStatus.GAP;
        runtime.statusMessage = "웹소켓 재연결 후 결측 보정 중입니다";
        scheduleReconcile(runtime, myGeneration);
    }

    private void onStreamError(IntervalRuntime runtime, int myGeneration, Throwable error) {
        if (!isCurrentGeneration(myGeneration)) {
            return;
        }
        runtime.status = runtime.connectedOnce.get() ? MarketDataStatus.GAP : MarketDataStatus.ERROR;
        runtime.statusMessage = runtime.connectedOnce.get()
                ? "웹소켓 재연결을 기다리는 중입니다"
                : "웹소켓 연결 실패: " + safeMessage(error);
    }

    private void scheduleReconcile(IntervalRuntime runtime, int myGeneration) {
        if (!isCurrentGeneration(myGeneration) || !runtime.reconcilePending.compareAndSet(false, true)) {
            return;
        }
        backfillExecutor.execute(() -> {
            try {
                if (!isCurrentGeneration(myGeneration)) {
                    return;
                }
                runtime.status = MarketDataStatus.GAP;
                List<BinanceKline> refreshed = restClient.fetchLatestClosedFutures(
                        SYMBOL, runtime.interval, SEED_COUNT);
                if (refreshed.isEmpty()) {
                    throw new IllegalStateException("결측 보정용 선물 확정봉 응답이 비어 있습니다");
                }
                if (!isCurrentGeneration(myGeneration)) {
                    return;
                }
                runtime.buffer.seed(refreshed);
                runtime.status = MarketDataStatus.READY;
                runtime.statusMessage = "";
            } catch (Exception e) {
                if (isCurrentGeneration(myGeneration)) {
                    runtime.status = MarketDataStatus.ERROR;
                    runtime.statusMessage = "결측 보정 실패: " + safeMessage(e);
                }
            } finally {
                runtime.reconcilePending.set(false);
            }
        });
    }

    private void onMessage(IntervalRuntime runtime, int myGeneration, String json) {
        if (!isCurrentGeneration(myGeneration)) {
            return;
        }
        KlineStreamEventParser.KlineStreamEvent event = eventParser.parse(json);
        if (event == null || !isCompatible(runtime.interval, event.kline())) {
            runtime.status = MarketDataStatus.GAP;
            runtime.statusMessage = "웹소켓 kline 형식 또는 interval이 올바르지 않습니다";
            scheduleReconcile(runtime, myGeneration);
            return;
        }
        try {
            if (event.closed()) {
                runtime.buffer.appendClosed(event.kline());
            } else {
                runtime.buffer.updatePartial(event.kline());
            }
            if (runtime.connectedOnce.get() && runtime.status == MarketDataStatus.STALE) {
                runtime.status = MarketDataStatus.READY;
                runtime.statusMessage = "";
            }
        } catch (RuntimeException e) {
            runtime.status = MarketDataStatus.GAP;
            runtime.statusMessage = "실시간 확정봉 결측이 감지되어 보정 중입니다";
            scheduleReconcile(runtime, myGeneration);
        }
    }

    private boolean isCompatible(BinanceKlineInterval interval, BinanceKline kline) {
        return kline != null && kline.openTimeMs() >= 0
                && kline.openTimeMs() % interval.intervalMs() == 0
                && kline.closeTimeMs() >= kline.openTimeMs();
    }

    private boolean isCurrentGeneration(int expectedGeneration) {
        return leader && generation.get() == expectedGeneration;
    }

    private void stopStream(IntervalRuntime runtime) {
        BinanceWebSocketStream stream;
        synchronized (runtime) {
            stream = runtime.activeStream;
            runtime.activeStream = null;
        }
        if (stream != null) {
            stream.disconnect();
        }
    }

    private String safeMessage(Throwable error) {
        return error == null || error.getMessage() == null ? "알 수 없는 오류" : error.getMessage();
    }

    /** 기존 단일 1분봉 테스트가 검증하는 순수 변환 계약을 유지한다. */
    static MarketSnapshotDto toDto(LiveKlineBuffer.Snapshot snapshot, String symbol, String marketType, String interval) {
        List<BinanceKline> closed = snapshot.closedCandles();
        BinanceKline partial = snapshot.currentPartial();
        BinanceKline latestClosed = closed.isEmpty() ? null : closed.get(closed.size() - 1);
        BigDecimal currentPrice = partial != null ? partial.closePrice()
                : latestClosed != null ? latestClosed.closePrice() : null;
        BigDecimal windowHigh = null;
        BigDecimal windowLow = null;
        BigDecimal changePercent = null;
        if (!closed.isEmpty()) {
            windowHigh = closed.get(0).highPrice();
            windowLow = closed.get(0).lowPrice();
            for (BinanceKline kline : closed) {
                windowHigh = windowHigh.max(kline.highPrice());
                windowLow = windowLow.min(kline.lowPrice());
            }
            BigDecimal windowStartPrice = closed.get(0).openPrice();
            if (currentPrice != null && windowStartPrice.signum() != 0) {
                changePercent = currentPrice.subtract(windowStartPrice)
                        .divide(windowStartPrice, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
            }
        }
        boolean hasGap = KlineSeriesValidator.hasGap(closed, 60_000L);
        BigDecimal rsi14 = hasGap ? null : RsiCalculator.calculate(closed, 14);
        MacdCalculator.Result macd = hasGap ? null : MacdCalculator.calculate(closed, 12, 26, 9);
        SupertrendCalculator.Result supertrend = hasGap ? null : SupertrendCalculator.calculate(closed, 10, 3);
        return new MarketSnapshotDto(symbol, marketType, interval, closed.size(), snapshot.lastAcceptedAtMs(),
                currentPrice, windowHigh, windowLow, changePercent, rsi14,
                macd == null ? null : macd.macdLine(), macd == null ? null : macd.signalLine(),
                macd == null ? null : macd.histogram(), supertrend == null ? null : supertrend.value(),
                supertrend == null ? null : supertrend.uptrend());
    }

    @PreDestroy
    public void shutdown() {
        for (IntervalRuntime runtime : runtimes.values()) {
            stopStream(runtime);
        }
        streamScheduler.shutdownNow();
        backfillExecutor.shutdownNow();
    }

    private static final class IntervalRuntime {
        private final BinanceKlineInterval interval;
        private final LiveKlineBuffer buffer;
        private final AtomicBoolean connectedOnce = new AtomicBoolean(false);
        private final AtomicBoolean reconcilePending = new AtomicBoolean(false);
        private volatile MarketDataStatus status = MarketDataStatus.BACKFILLING;
        private volatile String statusMessage = "리더 선출을 기다리는 중입니다";
        private volatile BinanceWebSocketStream activeStream;

        private IntervalRuntime(BinanceKlineInterval interval) {
            this.interval = interval;
            this.buffer = new LiveKlineBuffer(BUFFER_MAX_SIZE, interval.intervalMs());
        }
    }

    @FunctionalInterface
    interface StreamFactory {
        BinanceWebSocketStream create(String url, String logLabel, BinanceWebSocketStream.MessageListener listener,
                                      ScheduledExecutorService scheduler, long reconnectDelaySeconds);
    }
}
