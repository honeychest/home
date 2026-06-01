package com.chs.springboot.domain.upbit.service;

import com.chs.springboot.domain.binance.service.BinanceWebSocketStream;
import com.chs.springboot.domain.binance.service.NotificationService;
import com.chs.springboot.domain.upbit.websocket.UpbitPriceWebSocketHandler;
import com.chs.springboot.global.monitor.feed.FeedHealthConfig;
import com.chs.springboot.global.monitor.feed.FeedHealthRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.mockito.Mockito.*;

class UpbitStreamServiceTest {

    private UpbitPriceWebSocketHandler handler;
    private NotificationService notificationService;
    private FeedHealthRegistry feedHealthRegistry;
    private BinanceWebSocketStream stream;
    private BinanceWebSocketStream.MessageListener capturedListener;
    private UpbitStreamService service;

    @BeforeEach
    void setUp() {
        handler = mock(UpbitPriceWebSocketHandler.class);
        notificationService = mock(NotificationService.class);
        feedHealthRegistry = mock(FeedHealthRegistry.class);
        stream = mock(BinanceWebSocketStream.class);
        service = new UpbitStreamService(handler, notificationService, feedHealthRegistry,
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
    @DisplayName("연결되면 subscribe payload를 전송한다")
    void onConnected_sendsSubscribePayload() {
        WebSocket ws = mock(WebSocket.class);
        when(ws.sendText(anyString(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(ws));
        service.connect();

        ArgumentCaptor<Consumer<WebSocket>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(stream).onConnected(captor.capture());
        captor.getValue().accept(ws);

        verify(ws).sendText(contains("ticker"), eq(true));
    }

    @Test
    @DisplayName("메시지 수신 시 upbit 피드 수신을 기록한다")
    void onMessage_marksUpbitReceived() {
        service.connect();

        capturedListener.onMessage("{\"code\":\"KRW-BTC\",\"trade_price\":1}");

        verify(feedHealthRegistry).markReceived(FeedHealthConfig.UPBIT);
    }

    @Test
    @DisplayName("세션이 있으면 가격을 중계한다")
    void onMessage_withSessions_broadcasts() {
        when(handler.getSessionCount()).thenReturn(1);
        service.connect();

        capturedListener.onMessage("{\"code\":\"KRW-BTC\",\"trade_price\":1}");

        verify(handler).broadcastPrice("{\"code\":\"KRW-BTC\",\"trade_price\":1}");
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
