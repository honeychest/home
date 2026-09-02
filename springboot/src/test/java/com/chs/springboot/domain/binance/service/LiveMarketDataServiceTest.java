package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.BinanceKline;
import com.chs.springboot.domain.binance.model.BinanceKlineInterval;
import com.chs.springboot.global.redis.LeadershipChangedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.net.http.WebSocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LiveMarketDataServiceTest {

    private LiveMarketDataService service;
    private final List<String> streamUrls = new CopyOnWriteArrayList<>();
    private final java.util.Map<String, Consumer<WebSocket>> connectedListeners = new ConcurrentHashMap<>();

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    @Test
    void leaderEventCreatesFourFuturesStreamsAndDuplicateEventDoesNotCreateMore() throws Exception {
        BinanceKlineRestClient restClient = mock(BinanceKlineRestClient.class);
        List<BinanceWebSocketStream> streams = new CopyOnWriteArrayList<>();
        when(restClient.fetchLatestClosedFutures(anyString(), any(), eq(1_000))).thenReturn(List.of(candle(0, 1)));
        service = newService(restClient, streams, null);
        LeadershipChangedEvent event = new LeadershipChangedEvent("server-A", true, "owner", 1L);

        service.onLeadershipChanged(event);
        awaitSize(streams, 4);
        service.onLeadershipChanged(event);
        Thread.sleep(100);

        assertThat(streams).hasSize(4);
        assertThat(streamUrls).containsExactlyInAnyOrder(
                "wss://fstream.binance.com/market/ws/btcusdt@kline_1m",
                "wss://fstream.binance.com/market/ws/btcusdt@kline_5m",
                "wss://fstream.binance.com/market/ws/btcusdt@kline_15m",
                "wss://fstream.binance.com/market/ws/btcusdt@kline_4h");
        for (BinanceWebSocketStream stream : streams) {
            verify(stream).connect();
        }
    }

    @Test
    void backfillResultIsDiscardedWhenLeadershipIsLost() throws Exception {
        BinanceKlineRestClient restClient = mock(BinanceKlineRestClient.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(restClient.fetchLatestClosedFutures(anyString(), any(), eq(1_000))).thenAnswer(invocation -> {
            started.countDown();
            release.await(1, TimeUnit.SECONDS);
            return List.of(candle(0, 1));
        });
        List<BinanceWebSocketStream> streams = new CopyOnWriteArrayList<>();
        service = newService(restClient, streams, null);

        service.onLeadershipChanged(new LeadershipChangedEvent("server-A", true, "owner", 1L));
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        service.onLeadershipChanged(new LeadershipChangedEvent("server-A", false, "owner", 1L));
        release.countDown();
        Thread.sleep(150);

        assertThat(service.buildSnapshot().intervals()).allSatisfy(interval -> {
            assertThat(interval.closedCandles()).isEmpty();
            assertThat(interval.status().name()).isEqualTo("ERROR");
        });
        assertThat(streams).isEmpty();
    }

    @Test
    void streamCreatedJustBeforeLeadershipLossIsDisconnectedWithoutConnect() throws Exception {
        BinanceKlineRestClient restClient = mock(BinanceKlineRestClient.class);
        when(restClient.fetchLatestClosedFutures(anyString(), any(), eq(1_000))).thenReturn(List.of(candle(0, 1)));
        CountDownLatch factoryEntered = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        List<BinanceWebSocketStream> streams = new CopyOnWriteArrayList<>();
        service = newService(restClient, streams, new FactoryGate(factoryEntered, releaseFactory));

        service.onLeadershipChanged(new LeadershipChangedEvent("server-A", true, "owner", 1L));
        assertThat(factoryEntered.await(1, TimeUnit.SECONDS)).isTrue();
        service.onLeadershipChanged(new LeadershipChangedEvent("server-A", false, "owner", 1L));
        releaseFactory.countDown();
        Thread.sleep(150);

        assertThat(streams).isNotEmpty();
        for (BinanceWebSocketStream stream : streams) {
            verify(stream).disconnect();
            verify(stream, never()).connect();
        }
    }

    @Test
    void reconnectingOneIntervalReconcilesOnlyThatInterval() throws Exception {
        BinanceKlineRestClient restClient = mock(BinanceKlineRestClient.class);
        AtomicInteger fetchCount = new AtomicInteger();
        List<BinanceKlineInterval> fetchedIntervals = new CopyOnWriteArrayList<>();
        when(restClient.fetchLatestClosedFutures(anyString(), any(), eq(1_000))).thenAnswer(invocation -> {
            fetchCount.incrementAndGet();
            fetchedIntervals.add(invocation.getArgument(1));
            return List.of(candle(0, 1));
        });
        List<BinanceWebSocketStream> streams = new CopyOnWriteArrayList<>();
        service = newService(restClient, streams, null);

        service.onLeadershipChanged(new LeadershipChangedEvent("server-A", true, "owner", 1L));
        awaitSize(streams, 4);
        for (Consumer<WebSocket> listener : connectedListeners.values()) {
            listener.accept(mock(WebSocket.class));
        }
        connectedListeners.get("wss://fstream.binance.com/market/ws/btcusdt@kline_1m")
                .accept(mock(WebSocket.class));

        awaitCount(fetchCount, 5);
        assertThat(service.buildSnapshot().intervals()).allSatisfy(interval ->
                assertThat(interval.status().name()).isEqualTo("READY"));
        assertThat(fetchCount).hasValue(5);
        assertThat(fetchedIntervals).contains(BinanceKlineInterval.ONE_MINUTE,
                BinanceKlineInterval.FIVE_MINUTES, BinanceKlineInterval.FIFTEEN_MINUTES,
                BinanceKlineInterval.FOUR_HOURS);
        assertThat(fetchedIntervals.stream().filter(interval -> interval == BinanceKlineInterval.ONE_MINUTE).count())
                .isEqualTo(2);
    }

    private LiveMarketDataService newService(BinanceKlineRestClient restClient,
                                             List<BinanceWebSocketStream> streams,
                                             FactoryGate gate) {
        LiveMarketDataService.StreamFactory factory = (url, label, listener, scheduler, delay) -> {
            streamUrls.add(url);
            if (gate != null) {
                gate.entered().countDown();
                try {
                    gate.release().await(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            BinanceWebSocketStream stream = mock(BinanceWebSocketStream.class);
            streams.add(stream);
            doAnswer(invocation -> {
                connectedListeners.put(url, invocation.getArgument(0));
                return null;
            }).when(stream).onConnected(any());
            return stream;
        };
        return new LiveMarketDataService(restClient, new KlineStreamEventParser(mock(com.fasterxml.jackson.databind.ObjectMapper.class)),
                factory, Clock.fixed(Instant.ofEpochMilli(1700000000000L), ZoneOffset.UTC));
    }

    private void awaitSize(List<?> values, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (values.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(values).hasSize(expected);
    }

    private void awaitCount(AtomicInteger value, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (value.get() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    private static BinanceKline candle(long openTimeMs, int close) {
        BigDecimal price = BigDecimal.valueOf(close);
        return new BinanceKline(openTimeMs, price, price, price, price, BigDecimal.ONE,
                openTimeMs + 59_999L, BigDecimal.ONE, 1L, BigDecimal.ONE, BigDecimal.ONE);
    }

    private record FactoryGate(CountDownLatch entered, CountDownLatch release) {
    }
}
