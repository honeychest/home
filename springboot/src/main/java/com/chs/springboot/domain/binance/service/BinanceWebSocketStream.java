package com.chs.springboot.domain.binance.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public class BinanceWebSocketStream {

    private static final Logger log = LoggerFactory.getLogger(BinanceWebSocketStream.class);

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
    /** 연속 stale 임계 — 초과 시 connector(HttpClient)를 재생성한다. */
    private static final int STALE_RECREATE_THRESHOLD = 5;
    /** abort 비동기 호출이 hung socket 으로 반환되지 않을 때 강제로 끊는 타임아웃(초). */
    private static final long ABORT_TIMEOUT_SECONDS = 3;

    private final String url;
    private final String logLabel;
    private final MessageListener listener;
    private final ScheduledExecutorService scheduler;
    private final long reconnectDelaySeconds;
    private final Supplier<WebSocketConnector> connectorFactory;
    private volatile WebSocketConnector connector;

    private final LongSupplier nanoSource;
    private final long staleCheckIntervalMs;
    private final long staleThresholdNanos;

    /**
     * watchdog 전용 단일 스레드 풀.
     * I/O(연결/재연결) 풀과 격리해 abort/connect 가 hang 해도 stale 감시가 멈추지 않게 한다.
     */
    private final ScheduledExecutorService watchdogScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "bws-watchdog");
                t.setDaemon(true);
                return t;
            });

    /**
     * abort 전용 데몬 풀.
     * hung socket 의 abort() 가 반환되지 않아도 commonPool/watchdog/reconnect 가
     * 잠식되지 않도록 격리한다(daemon 이라 JVM 종료를 막지 않는다).
     */
    private final ExecutorService abortExecutor =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "bws-abort");
                t.setDaemon(true);
                return t;
            });

    private volatile boolean running = true;
    private final AtomicInteger generation = new AtomicInteger(0);
    private final AtomicBoolean reconnectPending = new AtomicBoolean(false);
    private volatile WebSocket webSocket;

    private final AtomicLong lastMessageAtNanos = new AtomicLong();
    private final AtomicInteger consecutiveStaleCount = new AtomicInteger(0);
    private final AtomicBoolean watchdogStarted = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> watchdogTask;
    private volatile Consumer<Throwable> errorListener;
    private volatile Consumer<WebSocket> connectedListener;

    public BinanceWebSocketStream(String url, String logLabel, MessageListener listener,
                                   ScheduledExecutorService scheduler, long reconnectDelaySeconds) {
        this(url, logLabel, listener, scheduler, reconnectDelaySeconds,
                BinanceWebSocketStream::createConnector);
    }

    public BinanceWebSocketStream(String url, String logLabel, MessageListener listener,
                                   ScheduledExecutorService scheduler, long reconnectDelaySeconds,
                                   WebSocketConnector connector) {
        this(url, logLabel, listener, scheduler, reconnectDelaySeconds, () -> connector);
    }

    private BinanceWebSocketStream(String url, String logLabel, MessageListener listener,
                                   ScheduledExecutorService scheduler, long reconnectDelaySeconds,
                                   Supplier<WebSocketConnector> connectorFactory) {
        this(url, logLabel, listener, scheduler, reconnectDelaySeconds, connectorFactory,
                System::nanoTime, DEFAULT_STALE_CHECK_INTERVAL_MS, DEFAULT_STALE_THRESHOLD_NANOS);
    }

    BinanceWebSocketStream(String url, String logLabel, MessageListener listener,
                           ScheduledExecutorService scheduler, long reconnectDelaySeconds,
                           WebSocketConnector connector,
                           LongSupplier nanoSource, long staleCheckIntervalMs, long staleThresholdNanos) {
        this(url, logLabel, listener, scheduler, reconnectDelaySeconds, () -> connector,
                nanoSource, staleCheckIntervalMs, staleThresholdNanos);
    }

    BinanceWebSocketStream(String url, String logLabel, MessageListener listener,
                           ScheduledExecutorService scheduler, long reconnectDelaySeconds,
                           Supplier<WebSocketConnector> connectorFactory,
                           LongSupplier nanoSource, long staleCheckIntervalMs, long staleThresholdNanos) {
        this.url = url;
        this.logLabel = logLabel;
        this.listener = listener;
        this.scheduler = scheduler;
        this.reconnectDelaySeconds = reconnectDelaySeconds;
        this.connectorFactory = connectorFactory;
        this.connector = connectorFactory.get();
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
        watchdogTask = watchdogScheduler.scheduleAtFixedRate(this::checkStale,
                staleCheckIntervalMs, staleCheckIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void checkStale() {
        // scheduleAtFixedRate 태스크가 예외를 던지면 이후 실행이 영구 중단되므로(JDK 스펙)
        // 전체를 Throwable 로 감싸 watchdog 스레드를 살려 둔다.
        try {
            if (!running) return;
            long elapsed = nanoSource.getAsLong() - lastMessageAtNanos.get();
            if (elapsed < staleThresholdNanos) return;

            int staleCount = consecutiveStaleCount.incrementAndGet();
            log.warn("[{}] stale 감지: {}ms 동안 메시지 없음 (연속 {}회) — 재연결 시도", logLabel,
                    TimeUnit.NANOSECONDS.toMillis(elapsed), staleCount);
            notifyError(new IllegalStateException("stale: " + TimeUnit.NANOSECONDS.toMillis(elapsed) + "ms 동안 메시지 없음"));
            // lastMessage를 현재로 갱신해 동일 stale에 대한 중복 트리거를 막는다.
            lastMessageAtNanos.set(nanoSource.getAsLong());

            // generation 증가만으론 hung 상태 HttpClient 가 복구되지 않으므로,
            // 연속 stale 이 임계를 넘으면 connector(HttpClient) 를 통째로 재생성한다.
            if (staleCount >= STALE_RECREATE_THRESHOLD) {
                log.warn("[{}] 연속 stale {}회 — connector(HttpClient) 재생성", logLabel, staleCount);
                connector = connectorFactory.get();
                consecutiveStaleCount.set(0);
            }

            abortAsync(webSocket);
            scheduleReconnect();
        } catch (Throwable t) {
            log.error("[{}] checkStale 처리 중 예외 — watchdog 유지", logLabel, t);
        }
    }

    /**
     * abort 를 watchdog 스레드 밖에서 실행하고 타임아웃을 건다.
     * hung socket 의 abort() 가 OS TCP keepalive 만료까지 반환하지 않아도
     * watchdog 스레드는 즉시 다음 tick 으로 넘어갈 수 있다.
     */
    private void abortAsync(WebSocket ws) {
        if (ws == null) return;
        CompletableFuture
                .runAsync(() -> {
                    try {
                        ws.abort();
                    } catch (Exception e) {
                        log.warn("[{}] stale 연결 abort 실패: {}", logLabel, e.getMessage());
                    }
                }, abortExecutor)
                .orTimeout(ABORT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    log.warn("[{}] abort 타임아웃/실패: {}", logLabel, ex.getMessage());
                    return null;
                });
    }

    public void disconnect() {
        running = false;
        ScheduledFuture<?> task = watchdogTask;
        if (task != null) {
            task.cancel(false);
        }
        watchdogScheduler.shutdownNow();
        abortExecutor.shutdownNow();
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
                            // 연결 성공 = 정상 복구. 연속 stale 카운터를 초기화한다.
                            consecutiveStaleCount.set(0);
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

    private static WebSocketConnector createConnector() {
        HttpClient client = HttpClient.newHttpClient();
        return (uri, listener) -> client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(uri, listener);
    }
}
