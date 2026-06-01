// [AGENT] 역할: Binance aggTrade WebSocket 스트림 구독 서비스 (SPOT/FUTURES) | 연관파일: LeaderElectionService.java(server-leader lease), AggTradeParser.java(JSON→Event), AggTradeSink.java(어댑터 SPI), BinanceWebSocketStream.java(재연결 공통) | 핵심: 리더 획득 시 BTCUSDT/ENAUSDT SPOT/FUTURES 4개 스트림 연결, 메시지 1회 파싱 후 등록된 모든 AggTradeSink 로 dispatch
package com.chs.springboot.domain.binance.service;

import com.chs.springboot.global.monitor.feed.FeedHealthConfig;
import com.chs.springboot.global.monitor.feed.FeedHealthRegistry;
import com.chs.springboot.global.redis.LeaderElectionService;
import com.chs.springboot.global.redis.LeadershipChangedEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Service
public class AggTradeStreamService {

    private static final Logger log = LoggerFactory.getLogger(AggTradeStreamService.class);

    private static final String SPOT_WS_BASE    = "wss://stream.binance.com:9443/ws/";
    private static final String FUTURES_WS_BASE = "wss://fstream.binance.com/market/ws/";
    private static final List<StreamSpec> STREAM_SPECS = List.of(
            new StreamSpec("btcusdt", "SPOT"),
            new StreamSpec("btcusdt", "FUTURES"),
            new StreamSpec("enausdt", "SPOT"),
            new StreamSpec("enausdt", "FUTURES")
    );

    private final AggTradeParser parser;
    private final List<AggTradeSink> sinks;
    private final StreamFactory streamFactory;
    private final FeedHealthRegistry feedHealthRegistry;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "aggtrade-stream");
                t.setDaemon(false);
                return t;
            });

    private final List<BinanceWebSocketStream> streams = new ArrayList<>();
    private volatile boolean connected = false;

    @Autowired
    public AggTradeStreamService(AggTradeParser parser, List<AggTradeSink> sinks,
                                 FeedHealthRegistry feedHealthRegistry) {
        this(parser, sinks, BinanceWebSocketStream::new, feedHealthRegistry);
    }

    AggTradeStreamService(AggTradeParser parser,
                          List<AggTradeSink> sinks,
                          StreamFactory streamFactory,
                          FeedHealthRegistry feedHealthRegistry) {
        this.parser = parser;
        this.sinks = sinks;
        this.streamFactory = streamFactory;
        this.feedHealthRegistry = feedHealthRegistry;
    }

    @PostConstruct
    public void start() {
        log.info("[AggTradeStream] ServerLeader 선출 대기: lease={} sinks={}",
                LeaderElectionService.SERVER_LEADER_LEASE,
                sinks.stream().map(s -> s.getClass().getSimpleName()).toList());
    }

    @EventListener
    public void onLeadershipChanged(LeadershipChangedEvent event) {
        if (event.leader()) {
            connectStreams();
        } else {
            disconnectStreams();
        }
    }

    private synchronized void connectStreams() {
        if (connected) {
            return;
        }
        streams.clear();
        STREAM_SPECS.stream()
                .map(this::createStream)
                .forEach(streams::add);
        streams.forEach(BinanceWebSocketStream::connect);
        connected = true;
        log.info("[AggTradeStream] 리더 획득: WebSocket stream 연결 완료 count={}", streams.size());
    }

    private synchronized void disconnectStreams() {
        if (!connected) {
            return;
        }
        streams.forEach(BinanceWebSocketStream::disconnect);
        streams.clear();
        connected = false;
        log.info("[AggTradeStream] 리더 상실: WebSocket stream 연결 해제");
    }

    private BinanceWebSocketStream createStream(StreamSpec spec) {
        String symbolUpper = spec.symbolLower().toUpperCase();
        String streamName  = spec.symbolLower() + "@aggTrade";
        String base        = "SPOT".equals(spec.marketType()) ? SPOT_WS_BASE : FUTURES_WS_BASE;
        String url         = base + streamName;
        String label       = "AggTradeStream/" + symbolUpper + "/" + spec.marketType();

        return streamFactory.create(url, label,
                json -> dispatch(json, symbolUpper, spec.marketType()),
                scheduler, 5);
    }

    private void dispatch(String rawJson, String symbol, String marketType) {
        feedHealthRegistry.markReceived(FeedHealthConfig.BINANCE_AGG_TRADE);
        AggTradeEvent event = parser.parse(rawJson, symbol, marketType, System.currentTimeMillis());
        for (AggTradeSink sink : sinks) {
            try {
                sink.accept(event);
            } catch (Exception e) {
                // sink 는 자기 실패를 자체 try/catch 로 처리하는 것이 계약.
                // 여기서는 sink 간 격리를 보장하기 위한 safety net.
                log.error("[AggTradeStream] sink {} 예외 전파 — 계약 위반 {} {} error={}",
                        sink.getClass().getSimpleName(), symbol, marketType, e.getMessage());
            }
        }
    }

    @PreDestroy
    public void stop() {
        disconnectStreams();
        scheduler.shutdownNow();
    }

    @FunctionalInterface
    public interface StreamFactory {
        BinanceWebSocketStream create(String url,
                                      String logLabel,
                                      BinanceWebSocketStream.MessageListener listener,
                                      ScheduledExecutorService scheduler,
                                      long reconnectDelaySeconds);
    }

    private record StreamSpec(String symbolLower, String marketType) {
    }
}
