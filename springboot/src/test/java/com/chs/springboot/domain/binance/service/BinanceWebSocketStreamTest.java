package com.chs.springboot.domain.binance.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.WebSocket;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BinanceWebSocketStreamTest {

    private ScheduledExecutorService scheduler;
    private AtomicInteger connectCount;
    private AtomicReference<WebSocket.Listener> capturedListener;
    private WebSocket mockWs;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        connectCount = new AtomicInteger();
        capturedListener = new AtomicReference<>();
        mockWs = mock(WebSocket.class);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    @DisplayName("1006 close 시 onClose+onError 동시 호출되어도 reconnect는 1회만 발생한다")
    void abnormalClose_onCloseAndOnError_reconnectsOnlyOnce() throws Exception {
        BinanceWebSocketStream stream = createStream(1);

        stream.connect();
        Thread.sleep(200);
        assertThat(connectCount.get()).isEqualTo(1);

        WebSocket.Listener listener = capturedListener.get();
        listener.onClose(mockWs, 1006, "abnormal closure");
        listener.onError(mockWs, new IOException("connection reset"));

        Thread.sleep(2500);

        assertThat(connectCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("정상 close 후에도 reconnect는 정확히 1회 발생한다")
    void normalClose_reconnectsOnce() throws Exception {
        BinanceWebSocketStream stream = createStream(1);

        stream.connect();
        Thread.sleep(200);

        capturedListener.get().onClose(mockWs, 1001, "going away");

        Thread.sleep(2500);

        assertThat(connectCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("disconnect 후에는 reconnect가 발생하지 않는다")
    void disconnect_preventsReconnect() throws Exception {
        BinanceWebSocketStream stream = createStream(1);

        stream.connect();
        Thread.sleep(200);

        stream.disconnect();
        capturedListener.get().onClose(mockWs, 1006, "abnormal closure");

        Thread.sleep(2500);

        assertThat(connectCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("close/error 없이 메시지만 끊긴 stale 연결도 watchdog이 재연결한다")
    void silentStale_watchdogReconnects() throws Exception {
        AtomicLong nanoClock = new AtomicLong(0);
        BinanceWebSocketStream stream = createStreamWithWatchdog(
                1, nanoClock::get, 20, TimeUnit.MILLISECONDS.toNanos(100));

        stream.connect();
        Thread.sleep(200);
        assertThat(connectCount.get()).isEqualTo(1);

        // close/error 이벤트 없이 시간만 흐른다 (silent stale).
        nanoClock.set(TimeUnit.MILLISECONDS.toNanos(500));

        Thread.sleep(2500);

        assertThat(connectCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("주기적으로 메시지를 받으면 watchdog이 재연결하지 않는다")
    void freshMessages_watchdogDoesNotReconnect() throws Exception {
        AtomicLong nanoClock = new AtomicLong(0);
        BinanceWebSocketStream stream = createStreamWithWatchdog(
                1, nanoClock::get, 20, TimeUnit.MILLISECONDS.toNanos(100));

        stream.connect();
        Thread.sleep(200);

        WebSocket.Listener listener = capturedListener.get();
        for (int i = 0; i < 10; i++) {
            nanoClock.addAndGet(TimeUnit.MILLISECONDS.toNanos(50));
            listener.onText(mockWs, "{\"x\":1}", true);
            Thread.sleep(50);
        }

        assertThat(connectCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("onOpen에 도달 못하는 stale가 임계(5회) 누적되면 connector(HttpClient)를 재생성한다")
    void consecutiveStale_recreatesConnector() throws Exception {
        AtomicInteger factoryCallCount = new AtomicInteger(0);
        // 항상 onOpen을 호출하지 않는 connector(영구 pending) → stale이 리셋 없이 누적된다.
        java.util.function.Supplier<BinanceWebSocketStream.WebSocketConnector> factory = () -> {
            factoryCallCount.incrementAndGet();
            return (uri, listener) -> {
                connectCount.incrementAndGet();
                return new CompletableFuture<>(); // 영원히 미완료(hung handshake 모사)
            };
        };

        BinanceWebSocketStream stream = new BinanceWebSocketStream(
                "wss://test.example.com/ws", "TEST", json -> {},
                scheduler, 0, factory,
                System::nanoTime, 20, TimeUnit.MILLISECONDS.toNanos(50));

        stream.connect();
        Thread.sleep(2000);

        // 생성 시 1회 + 연속 stale 5회 누적 후 최소 1회 재생성 = 2회 이상.
        assertThat(factoryCallCount.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("connector 재생성 후 정상 connector면 재연결되어 복구되고 추가 재생성은 멈춘다")
    void afterRecreate_recoversAndStopsRecreating() throws Exception {
        AtomicInteger factoryCallCount = new AtomicInteger(0);
        // 첫 connector는 hung, 재생성된 두 번째부터는 정상(onOpen 호출).
        java.util.function.Supplier<BinanceWebSocketStream.WebSocketConnector> factory = () -> {
            int n = factoryCallCount.incrementAndGet();
            if (n == 1) {
                return (uri, listener) -> {
                    connectCount.incrementAndGet();
                    return new CompletableFuture<>(); // hung
                };
            }
            return (uri, listener) -> {
                capturedListener.set(listener);
                connectCount.incrementAndGet();
                listener.onOpen(mockWs);
                return CompletableFuture.completedFuture(mockWs);
            };
        };

        BinanceWebSocketStream stream = new BinanceWebSocketStream(
                "wss://test.example.com/ws", "TEST", json -> {},
                scheduler, 0, factory,
                System::nanoTime, 20, TimeUnit.MILLISECONDS.toNanos(50));

        stream.connect();
        Thread.sleep(2500);

        // 재생성된 정상 connector의 onOpen에 도달(복구)했고,
        assertThat(capturedListener.get()).isNotNull();
        // 복구 후 onOpen이 stale 카운터를 리셋하므로 더 이상 재생성되지 않는다.
        assertThat(factoryCallCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("abort()가 hang 해도 watchdog 스레드는 막히지 않고 재연결을 계속한다")
    void abortHang_doesNotBlockWatchdog() throws Exception {
        // abort()가 5초간 블로킹(hung socket 모사)되도록 설정.
        doAnswer(inv -> { Thread.sleep(5000); return null; }).when(mockWs).abort();

        BinanceWebSocketStream stream = createStreamWithWatchdog(
                0, System::nanoTime, 20, TimeUnit.MILLISECONDS.toNanos(50));

        stream.connect();
        Thread.sleep(1200);

        // abort가 abortExecutor에서 hang 중이어도 watchdog은 살아 있어 여러 번 재연결한다.
        assertThat(connectCount.get()).isGreaterThanOrEqualTo(3);
    }

    private BinanceWebSocketStream createStreamWithWatchdog(long reconnectDelaySec,
                                                            java.util.function.LongSupplier nanoSource,
                                                            long staleCheckIntervalMs,
                                                            long staleThresholdNanos) {
        BinanceWebSocketStream.WebSocketConnector connector = (uri, listener) -> {
            capturedListener.set(listener);
            connectCount.incrementAndGet();
            listener.onOpen(mockWs);
            return CompletableFuture.completedFuture(mockWs);
        };

        return new BinanceWebSocketStream(
                "wss://test.example.com/ws", "TEST", json -> {},
                scheduler, reconnectDelaySec, connector,
                nanoSource, staleCheckIntervalMs, staleThresholdNanos);
    }

    private BinanceWebSocketStream createStream(long reconnectDelaySec) {
        BinanceWebSocketStream.WebSocketConnector connector = (uri, listener) -> {
            capturedListener.set(listener);
            connectCount.incrementAndGet();
            listener.onOpen(mockWs);
            return CompletableFuture.completedFuture(mockWs);
        };

        return new BinanceWebSocketStream(
                "wss://test.example.com/ws", "TEST", json -> {},
                scheduler, reconnectDelaySec, connector);
    }
}
