package com.chs.springboot.domain.binance.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public class BinanceWebSocketStream {

    private static final Logger log = LoggerFactory.getLogger(BinanceWebSocketStream.class);
    private static final WebSocketConnector SHARED_CONNECTOR = createSharedConnector();

    @FunctionalInterface
    public interface MessageListener {
        void onMessage(String json);
    }

    @FunctionalInterface
    public interface WebSocketConnector {
        CompletableFuture<WebSocket> connect(URI uri, WebSocket.Listener listener);
    }

    /** 메시지 수신이 없을 때 stale 판정까지의 기본 임계값(45초). */
    private static final long DEFAULT_STALE_THRESHOLD_NANOS = TimeUnit.SECONDS.toNanos(45);
    /** stale 검사 기본 주기(10초). */
    private static final long DEFAULT_STALE_CHECK_INTERVAL_MS = TimeUnit.SECONDS.toMillis(10);

    private final String url;
    private final String logLabel;
    private final MessageListener listener;
    private final ScheduledExecutorService scheduler;
    private final long reconnectDelaySeconds;
    private final WebSocketConnector connector;

    private final LongSupplier nanoSource;
    private final long staleCheckIntervalMs;
    private final long staleThresholdNanos;

    private volatile boolean running = true;
    private final AtomicInteger generation = new AtomicInteger(0);
    private final AtomicBoolean reconnectPending = new AtomicBoolean(false);
    private volatile WebSocket webSocket;

    private final AtomicLong lastMessageAtNanos = new AtomicLong();
    private final AtomicBoolean watchdogStarted = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> watchdogTask;
    private volatile Consumer<Throwable> errorListener;
    private volatile Consumer<WebSocket> connectedListener;

    public BinanceWebSocketStream(String url, String logLabel, MessageListener listener,
                                   ScheduledExecutorService scheduler, long reconnectDelaySeconds) {
        this(url, logLabel, listener, scheduler, reconnectDelaySeconds, SHARED_CONNECTOR);
    }

    public BinanceWebSocketStream(String url, String logLabel, MessageListener listener,
                                   ScheduledExecutorService scheduler, long reconnectDelaySeconds,
                                   WebSocketConnector connector) {
        this(url, logLabel, listener, scheduler, reconnectDelaySeconds, connector,
                System::nanoTime, DEFAULT_STALE_CHECK_INTERVAL_MS, DEFAULT_STALE_THRESHOLD_NANOS);
    }

    BinanceWebSocketStream(String url, String logLabel, MessageListener listener,
                           ScheduledExecutorService scheduler, long reconnectDelaySeconds,
                           WebSocketConnector connector,
                           LongSupplier nanoSource, long staleCheckIntervalMs, long staleThresholdNanos) {
        this.url = url;
        this.logLabel = logLabel;
        this.listener = listener;
        this.scheduler = scheduler;
        this.reconnectDelaySeconds = reconnectDelaySeconds;
        this.connector = connector;
        this.nanoSource = nanoSource;
        this.staleCheckIntervalMs = staleCheckIntervalMs;
        this.staleThresholdNanos = staleThresholdNanos;
    }

    /** upstream 오류/stale 발생 시 호출되는 콜백을 등록한다(알림 연동용). */
    public void onError(Consumer<Throwable> errorListener) {
        this.errorListener = errorListener;
    }

    /** 연결 직후(onOpen) 1회 호출되는 콜백을 등록한다(subscribe 전송 등). */
    public void onConnected(Consumer<WebSocket> connectedListener) {
        this.connectedListener = connectedListener;
    }

    private void notifyError(Throwable error) {
        Consumer<Throwable> l = errorListener;
        if (l != null) {
            try {
                l.accept(error);
            } catch (Exception e) {
                log.warn("[{}] errorListener 처리 실패: {}", logLabel, e.getMessage());
            }
        }
    }

    public void connect() {
        final int myGen = generation.incrementAndGet();
        reconnectPending.set(false);
        startWatchdog();
        scheduler.execute(() -> openStream(myGen));
    }

    private void startWatchdog() {
        if (!watchdogStarted.compareAndSet(false, true)) return;
        lastMessageAtNanos.set(nanoSource.getAsLong());
        watchdogTask = scheduler.scheduleAtFixedRate(this::checkStale,
                staleCheckIntervalMs, staleCheckIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void checkStale() {
        if (!running) return;
        long elapsed = nanoSource.getAsLong() - lastMessageAtNanos.get();
        if (elapsed < staleThresholdNanos) return;

        log.warn("[{}] stale 감지: {}ms 동안 메시지 없음 — 재연결 시도", logLabel,
                TimeUnit.NANOSECONDS.toMillis(elapsed));
        notifyError(new IllegalStateException("stale: " + TimeUnit.NANOSECONDS.toMillis(elapsed) + "ms 동안 메시지 없음"));
        // lastMessage를 현재로 갱신해 동일 stale에 대한 중복 트리거를 막는다.
        lastMessageAtNanos.set(nanoSource.getAsLong());
        WebSocket currentWebSocket = webSocket;
        if (currentWebSocket != null) {
            try {
                currentWebSocket.abort();
            } catch (Exception e) {
                log.warn("[{}] stale 연결 abort 실패: {}", logLabel, e.getMessage());
            }
        }
        scheduleReconnect();
    }

    public void disconnect() {
        running = false;
        ScheduledFuture<?> task = watchdogTask;
        if (task != null) {
            task.cancel(false);
        }
        WebSocket currentWebSocket = webSocket;
        if (currentWebSocket != null) {
            try {
                currentWebSocket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
            } catch (Exception e) {
                log.warn("[{}] 웹소켓 종료 실패: {}", logLabel, e.getMessage());
            }
        }
    }

    private void openStream(int myGen) {
        if (!running) return;
        try {
            log.info("[{}] 연결 시도 (gen={})", logLabel, myGen);
            connector.connect(URI.create(url), new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket ws) {
                            webSocket = ws;
                            lastMessageAtNanos.set(nanoSource.getAsLong());
                            log.info("[{}] 연결 성공 (gen={})", logLabel, myGen);
                            Consumer<WebSocket> onConnected = connectedListener;
                            if (onConnected != null) {
                                try {
                                    onConnected.accept(ws);
                                } catch (Exception e) {
                                    log.warn("[{}] connectedListener 처리 실패: {}", logLabel, e.getMessage());
                                }
                            }
                            ws.request(1);
                            WebSocket.Listener.super.onOpen(ws);
                        }

                        private final StringBuilder buffer = new StringBuilder();
                        private final java.io.ByteArrayOutputStream binaryBuffer = new java.io.ByteArrayOutputStream();

                        @Override
                        public java.util.concurrent.CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                            lastMessageAtNanos.set(nanoSource.getAsLong());
                            buffer.append(data);
                            if (last) {
                                String json = buffer.toString();
                                buffer.setLength(0);
                                try {
                                    listener.onMessage(json);
                                } catch (Exception e) {
                                    log.warn("[{}] 메시지 처리 실패: {}", logLabel, e.getMessage());
                                }
                            }
                            ws.request(1);
                            return null;
                        }

                        @Override
                        public java.util.concurrent.CompletionStage<?> onBinary(WebSocket ws, java.nio.ByteBuffer data, boolean last) {
                            lastMessageAtNanos.set(nanoSource.getAsLong());
                            byte[] chunk = new byte[data.remaining()];
                            data.get(chunk);
                            binaryBuffer.write(chunk, 0, chunk.length);
                            if (last) {
                                String json = binaryBuffer.toString(java.nio.charset.StandardCharsets.UTF_8);
                                binaryBuffer.reset();
                                try {
                                    listener.onMessage(json);
                                } catch (Exception e) {
                                    log.warn("[{}] 바이너리 메시지 처리 실패: {}", logLabel, e.getMessage());
                                }
                            }
                            ws.request(1);
                            return null;
                        }

                        @Override
                        public java.util.concurrent.CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                            log.warn("[{}] 종료 (gen={}, status={}): {}", logLabel, myGen, statusCode, reason);
                            if (myGen == generation.get()) scheduleReconnect();
                            return null;
                        }

                        @Override
                        public void onError(WebSocket ws, Throwable error) {
                            log.error("[{}] 오류 (gen={}): {}", logLabel, myGen, error.getMessage());
                            if (myGen == generation.get()) {
                                notifyError(error);
                                scheduleReconnect();
                            }
                        }
                    })
                    .whenComplete((ws, error) -> {
                        if (error != null) {
                            log.error("[{}] handshake 실패 (gen={}): {}", logLabel, myGen, error.getMessage());
                            if (myGen == generation.get()) {
                                notifyError(error);
                                scheduleReconnect();
                            }
                        }
                    });
        } catch (Exception e) {
            log.error("[{}] 연결 오류: {}", logLabel, e.getMessage());
            if (myGen == generation.get()) scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!running) return;
        if (!reconnectPending.compareAndSet(false, true)) return;
        scheduler.schedule(this::connect, reconnectDelaySeconds, TimeUnit.SECONDS);
    }

    private static WebSocketConnector createSharedConnector() {
        HttpClient client = HttpClient.newHttpClient();
        return (uri, listener) -> client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(uri, listener);
    }
}
