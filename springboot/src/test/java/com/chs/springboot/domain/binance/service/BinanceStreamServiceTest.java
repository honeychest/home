package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.websocket.BinancePriceWebSocketHandler;
import com.chs.springboot.global.monitor.feed.FeedHealthConfig;
import com.chs.springboot.global.monitor.feed.FeedHealthRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.function.Consumer;

import static org.mockito.Mockito.*;

class BinanceStreamServiceTest {

    private BinancePriceWebSocketHandler handler;
    private NotificationService notificationService;
    private FeedHealthRegistry feedHealthRegistry;
    private BinanceWebSocketStream stream;
    private BinanceWebSocketStream.MessageListener capturedListener;
    private BinanceStreamService service;

    @BeforeEach
    void setUp() {
        handler = mock(BinancePriceWebSocketHandler.class);
        notificationService = mock(NotificationService.class);
        feedHealthRegistry = mock(FeedHealthRegistry.class);
        stream = mock(BinanceWebSocketStream.class);
        service = new BinanceStreamService(handler, notificationService, feedHealthRegistry,
                mock(com.chs.springboot.global.monitor.health.WsReconnectMonitor.class),
                (url, logLabel, listener, scheduler, reconnectDelaySeconds) -> {
                    capturedListener = listener;
                    return stream;
                });
    }

    @Test
    @DisplayName("connect는 stream을 생성하고 연결한다")
    void connect_createsAndConnectsStream() {
        service.connect();

        verify(stream).connect();
    }

    @Test
    @DisplayName("메시지 수신 시 ticker 피드 수신을 기록한다")
    void onMessage_marksTickerReceived() {
        service.connect();

        capturedListener.onMessage("{\"data\":{\"s\":\"BTCUSDT\",\"c\":\"1\"}}");

        verify(feedHealthRegistry).markReceived(FeedHealthConfig.BINANCE_TICKER);
    }

    @Test
    @DisplayName("세션이 있으면 심볼별로 가격을 중계한다")
    void onMessage_withSessions_broadcastsBySymbol() {
        when(handler.getSessionCount()).thenReturn(1);
        service.connect();

        capturedListener.onMessage("{\"data\":{\"s\":\"BTCUSDT\",\"c\":\"1\"}}");

        verify(handler).broadcastPrice(anyString(), eq("BTCUSDT"));
    }

    @Test
    @DisplayName("upstream 오류 시 알림을 보낸다")
    void onError_sendsAlert() {
        service.connect();

        ArgumentCaptor<Consumer<Throwable>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(stream).onError(captor.capture());
        captor.getValue().accept(new IOException("connection reset"));

        verify(notificationService).sendAlert(contains("connection reset"));
    }
}
