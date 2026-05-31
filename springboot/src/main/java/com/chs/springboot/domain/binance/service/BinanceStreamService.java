package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.websocket.BinancePriceWebSocketHandler;
import com.chs.springboot.global.monitor.feed.FeedHealthConfig;
import com.chs.springboot.global.monitor.feed.FeedHealthRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

/**
 * Binance upstream: always-on + full subscription.
 * Client sessions only receive messages for their requested symbol.
 */
@Service
public class BinanceStreamService {

    private static final Logger log = LoggerFactory.getLogger(BinanceStreamService.class);

    private static final List<String> SUBSCRIBED_SYMBOLS = List.of(
            "btcusdt", "ethusdt", "solusdt", "xrpusdt"
    );

    private static final String STREAM_BASE_URL = "wss://stream.binance.com:9443/stream?streams=";
    private static final int RECONNECT_DELAY_SEC = 3;

    private final BinancePriceWebSocketHandler handler;
    private final NotificationService notificationService;
    private final FeedHealthRegistry feedHealthRegistry;
    private final AggTradeStreamService.StreamFactory streamFactory;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile BinanceWebSocketStream stream;

    @Autowired
    public BinanceStreamService(BinancePriceWebSocketHandler handler,
                                NotificationService notificationService,
                                FeedHealthRegistry feedHealthRegistry) {
        this(handler, notificationService, feedHealthRegistry, BinanceWebSocketStream::new);
    }

    BinanceStreamService(BinancePriceWebSocketHandler handler,
                         NotificationService notificationService,
                         FeedHealthRegistry feedHealthRegistry,
                         AggTradeStreamService.StreamFactory streamFactory) {
        this.handler = handler;
        this.notificationService = notificationService;
        this.feedHealthRegistry = feedHealthRegistry;
        this.streamFactory = streamFactory;
    }

    @PostConstruct
    public void connect() {
        String url = getStreamUrl();
        log.info("[BinanceStream] upstream connect (symbols={})", SUBSCRIBED_SYMBOLS);
        stream = streamFactory.create(url, "BinanceStream/ticker", this::onMessage,
                scheduler, RECONNECT_DELAY_SEC);
        stream.onError(error ->
                notificationService.sendAlert("[BinanceStream] error: " + error.getMessage()));
        stream.connect();
    }

    private void onMessage(String json) {
        feedHealthRegistry.markReceived(FeedHealthConfig.BINANCE_TICKER);
        if (handler.getSessionCount() > 0) {
            relayBySessionSymbol(json);
        }
    }

    private void relayBySessionSymbol(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);

            JsonNode payloadNode = root.path("data");
            JsonNode tickerNode = payloadNode.isMissingNode() ? root : payloadNode;

            String symbol = tickerNode.path("s").asText(null);
            if (symbol == null || symbol.isBlank()) return;

            String payload = payloadNode.isMissingNode() ? json : payloadNode.toString();
            handler.broadcastPrice(payload, symbol.toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            // Ignore malformed frames.
        }
    }

    private String getStreamUrl() {
        String streams = SUBSCRIBED_SYMBOLS.stream()
                .map(symbol -> symbol + "@ticker")
                .collect(Collectors.joining("/"));
        return STREAM_BASE_URL + streams;
    }

    @PreDestroy
    public void disconnect() {
        if (stream != null) {
            stream.disconnect();
        }
        scheduler.shutdownNow();
    }
}
