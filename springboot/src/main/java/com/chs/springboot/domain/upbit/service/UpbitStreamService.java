package com.chs.springboot.domain.upbit.service;

import com.chs.springboot.domain.binance.service.BinanceWebSocketStream;
import com.chs.springboot.domain.binance.service.NotificationService;
import com.chs.springboot.domain.binance.service.AggTradeStreamService;
import com.chs.springboot.domain.upbit.websocket.UpbitPriceWebSocketHandler;
import com.chs.springboot.global.monitor.feed.FeedHealthConfig;
import com.chs.springboot.global.monitor.feed.FeedHealthRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

/**
 * Upbit upstream: always-on + full subscription.
 * Client sessions only receive messages for their requested codes.
 */
@Service
public class UpbitStreamService {

    private static final Logger log = LoggerFactory.getLogger(UpbitStreamService.class);

    private static final String UPBIT_STREAM_URL = "wss://api.upbit.com/websocket/v1";
    private static final int RECONNECT_DELAY_SEC = 3;

    private static final List<String> SUBSCRIBED_CODES = List.of(
            "KRW-BTC", "KRW-ETH", "KRW-SOL", "KRW-XRP", "KRW-USDT"
    );

    private final UpbitPriceWebSocketHandler handler;
    private final NotificationService notificationService;
    private final FeedHealthRegistry feedHealthRegistry;
    private final AggTradeStreamService.StreamFactory streamFactory;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private volatile BinanceWebSocketStream stream;

    @Autowired
    public UpbitStreamService(UpbitPriceWebSocketHandler handler, NotificationService notificationService,
                              FeedHealthRegistry feedHealthRegistry) {
        this(handler, notificationService, feedHealthRegistry, BinanceWebSocketStream::new);
    }

    UpbitStreamService(UpbitPriceWebSocketHandler handler, NotificationService notificationService,
                       FeedHealthRegistry feedHealthRegistry,
                       AggTradeStreamService.StreamFactory streamFactory) {
        this.handler = handler;
        this.notificationService = notificationService;
        this.feedHealthRegistry = feedHealthRegistry;
        this.streamFactory = streamFactory;
    }

    @PostConstruct
    public void connect() {
        log.info("[UpbitStream] upstream connect (codes={})", SUBSCRIBED_CODES);
        stream = streamFactory.create(UPBIT_STREAM_URL, "UpbitStream/ticker", this::onMessage,
                scheduler, RECONNECT_DELAY_SEC);
        stream.onConnected(ws ->
                ws.sendText(buildSubscribePayload(), true)
                        .exceptionally(e -> {
                            log.error("[UpbitStream] subscribe send failed: {}", e.getMessage());
                            return null;
                        }));
        stream.onError(error ->
                notificationService.sendAlert("[UpbitStream] error: " + error.getMessage()));
        stream.connect();
    }

    private void onMessage(String json) {
        if (json == null || json.isEmpty()) return;
        feedHealthRegistry.markReceived(FeedHealthConfig.UPBIT);
        if (handler.getSessionCount() <= 0) return;
        handler.broadcastPrice(json);
    }

    private String buildSubscribePayload() {
        String joinedCodes = SUBSCRIBED_CODES.stream()
                .map(code -> "\"" + code + "\"")
                .collect(Collectors.joining(","));

        return "[{\"ticket\":\"upbit-ticker-server\"},"
                + "{\"type\":\"ticker\",\"codes\":[" + joinedCodes + "]}]";
    }

    @PreDestroy
    public void disconnect() {
        if (stream != null) {
            stream.disconnect();
        }
        scheduler.shutdownNow();
    }
}
