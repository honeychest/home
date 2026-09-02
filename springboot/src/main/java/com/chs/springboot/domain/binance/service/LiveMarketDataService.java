// [AGENT] 자동매매 판단용 실시간 1분봉을 인메모리로만 유지 (DB 미사용 — docs/binance/CONTEXT.md).
// 리더 노드에서만 동작(AggTradeStreamService와 같은 패턴) — 앱 2인스턴스 중복 수집 방지.
// 리더십 이벤트 콜백 안에서 REST를 기다리면 LeaderElectionService의 10초 lease 갱신(5초 주기)이
// 막힐 수 있어(Codex 리뷰 지적), 실제 초기적재·웹소켓 연결은 전용 스레드로 넘기고 세대(generation)
// 번호로 늦게 도착한 결과(이미 리더가 바뀐 뒤의 REST 응답·웹소켓 메시지)를 무시한다.
package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.BinanceKline;
import com.chs.springboot.domain.binance.model.MarketSnapshotDto;
import com.chs.springboot.global.redis.LeadershipChangedEvent;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class LiveMarketDataService {

    private static final Logger log = LoggerFactory.getLogger(LiveMarketDataService.class);

    static final String SYMBOL = "BTCUSDT";
    static final String MARKET_TYPE = "FUTURES";
    static final String INTERVAL = "1m";
    private static final String FUTURES_WS_BASE = "wss://fstream.binance.com/market/ws/";
    private static final String STREAM_NAME = "btcusdt@kline_1m";
    private static final int BUFFER_MAX_SIZE = 500;
    private static final long SEED_HOURS = 8L;
    /** 이 시간 동안 확정/진행봉이 하나도 안 들어오면 신선도 실패로 본다(1분봉 기준 여유 포함). */
    private static final long STALE_THRESHOLD_MS = 3 * 60_000L;

    private final BinanceKlineRangeFetcher rangeFetcher;
    private final KlineStreamEventParser eventParser;
    private final StreamFactory streamFactory;
    private final LiveKlineBuffer buffer = new LiveKlineBuffer(BUFFER_MAX_SIZE);

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "live-market-data");
                t.setDaemon(true);
                return t;
            });
    private final ScheduledExecutorService leadershipExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "live-market-data-leadership");
                t.setDaemon(true);
                return t;
            });

    private final AtomicInteger generation = new AtomicInteger(0);
    private volatile BinanceWebSocketStream activeStream;
    private volatile boolean leader = false;

    @Autowired
    public LiveMarketDataService(BinanceKlineRestClient restClient, KlineStreamEventParser eventParser) {
        this(new BinanceKlineRangeFetcher(restClient), eventParser, BinanceWebSocketStream::new);
    }

    LiveMarketDataService(BinanceKlineRangeFetcher rangeFetcher, KlineStreamEventParser eventParser,
                          StreamFactory streamFactory) {
        this.rangeFetcher = rangeFetcher;
        this.eventParser = eventParser;
        this.streamFactory = streamFactory;
    }

    @EventListener
    public void onLeadershipChanged(LeadershipChangedEvent event) {
        int myGen = generation.incrementAndGet();
        if (event.leader()) {
            leader = true;
            leadershipExecutor.execute(() -> startForGeneration(myGen));
        } else {
            leader = false;
            stopStream();
        }
    }

    public boolean isLeader() {
        return leader;
    }

    public boolean isStale() {
        long lastAcceptedAtMs = buffer.snapshot().lastAcceptedAtMs();
        if (lastAcceptedAtMs == 0) {
            return true;
        }
        return System.currentTimeMillis() - lastAcceptedAtMs > STALE_THRESHOLD_MS;
    }

    public MarketSnapshotDto buildSnapshotDto() {
        return toDto(buffer.snapshot(), SYMBOL, MARKET_TYPE, INTERVAL);
    }

    private static final int RSI_PERIOD = 14;
    private static final int MACD_FAST = 12;
    private static final int MACD_SLOW = 26;
    private static final int MACD_SIGNAL = 9;
    private static final int SUPERTREND_ATR_PERIOD = 10;
    private static final int SUPERTREND_MULTIPLIER = 3;

    /** 버퍼 스냅샷 → 응답 DTO 변환. 순수 함수라 버퍼 없이도 테스트 가능. */
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
            for (BinanceKline k : closed) {
                windowHigh = windowHigh.max(k.highPrice());
                windowLow = windowLow.min(k.lowPrice());
            }
            BigDecimal windowStartPrice = closed.get(0).openPrice();
            if (currentPrice != null && windowStartPrice.signum() != 0) {
                changePercent = currentPrice.subtract(windowStartPrice)
                        .divide(windowStartPrice, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
            }
        }

        // 확정봉 사이에 결측(재연결 등으로 1분 캔들이 비는 경우)이 있으면 지표를 안 낸다 —
        // 결측을 무시하고 이어붙이면 RSI/MACD가 실제와 다른 기간으로 계산된다(Codex 리뷰 지적).
        boolean hasGap = hasGap(closed);
        BigDecimal rsi14 = hasGap ? null : RsiCalculator.calculate(closed, RSI_PERIOD);
        MacdCalculator.Result macd = hasGap ? null
                : MacdCalculator.calculate(closed, MACD_FAST, MACD_SLOW, MACD_SIGNAL);
        SupertrendCalculator.Result supertrend = hasGap ? null
                : SupertrendCalculator.calculate(closed, SUPERTREND_ATR_PERIOD, SUPERTREND_MULTIPLIER);

        return new MarketSnapshotDto(symbol, marketType, interval, closed.size(),
                snapshot.lastAcceptedAtMs(), currentPrice, windowHigh, windowLow, changePercent,
                rsi14,
                macd != null ? macd.macdLine() : null,
                macd != null ? macd.signalLine() : null,
                macd != null ? macd.histogram() : null,
                supertrend != null ? supertrend.value() : null,
                supertrend != null ? supertrend.uptrend() : null);
    }

    /** 확정봉 목록에 1분 경계가 아닌 간격(결측)이 있는지 확인. */
    private static boolean hasGap(List<BinanceKline> closed) {
        for (int i = 1; i < closed.size(); i++) {
            if (closed.get(i).openTimeMs() - closed.get(i - 1).openTimeMs() != BinanceKlineWindow.INTERVAL_MS) {
                return true;
            }
        }
        return false;
    }

    private void startForGeneration(int myGen) {
        try {
            long nowMs = System.currentTimeMillis();
            long toMsExclusive = BinanceKlineWindow.safeEnd(nowMs);
            long fromMs = Math.max(0L, toMsExclusive - SEED_HOURS * BinanceKlineWindow.HOUR_MS);
            List<BinanceKline> seed = rangeFetcher.fetch(SYMBOL, MARKET_TYPE, fromMs, toMsExclusive).klines();
            if (generation.get() != myGen) {
                log.info("[LiveMarketData] 초기 적재 완료 전 리더십 변경 — 결과 폐기 (gen={})", myGen);
                return;
            }
            buffer.seed(seed);
            log.info("[LiveMarketData] 초기 적재 완료 {}건 (gen={})", seed.size(), myGen);
        } catch (Exception e) {
            log.warn("[LiveMarketData] 초기 적재 실패 — 웹소켓 실시간 데이터만으로 이어감 (gen={}): {}",
                    myGen, e.getMessage());
        }

        if (generation.get() != myGen) {
            return;
        }
        connectStream(myGen);
    }

    private void connectStream(int myGen) {
        String url = FUTURES_WS_BASE + STREAM_NAME;
        BinanceWebSocketStream stream = streamFactory.create(url, "LiveMarketData/" + SYMBOL,
                json -> onMessage(myGen, json), scheduler, 5);
        activeStream = stream;
        stream.connect();
    }

    private void onMessage(int myGen, String json) {
        if (generation.get() != myGen) {
            return;
        }
        KlineStreamEventParser.KlineStreamEvent event = eventParser.parse(json);
        if (event == null) {
            return;
        }
        if (event.closed()) {
            buffer.appendClosed(event.kline());
        } else {
            buffer.updatePartial(event.kline());
        }
    }

    private void stopStream() {
        BinanceWebSocketStream stream = activeStream;
        if (stream != null) {
            stream.disconnect();
            activeStream = null;
        }
    }

    @PreDestroy
    public void shutdown() {
        stopStream();
        scheduler.shutdownNow();
        leadershipExecutor.shutdownNow();
    }

    @FunctionalInterface
    interface StreamFactory {
        BinanceWebSocketStream create(String url, String logLabel, BinanceWebSocketStream.MessageListener listener,
                                      ScheduledExecutorService scheduler, long reconnectDelaySeconds);
    }
}
