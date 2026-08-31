// [AGENT] 1분봉·5분봉·15분봉 캔들 원천 조회 → WS 브로드캐스트
// 완료봉은 SignalCandleSource의 hybrid 원천을 주기적으로 확인하고, 진행봉은 temp shadow를 부분 집계한다.
package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.event.Candle1mCompletedEvent;
import com.chs.springboot.domain.binance.model.event.CandleCompletedEvent;
import com.chs.springboot.domain.binance.websocket.CandleWebSocketHandler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class CandleStreamService {

    private final CandleWebSocketHandler candleWebSocketHandler;
    private final ObjectMapper objectMapper;
    private final SignalCandleSource candleSource;
    private final Map<String, Long> lastClosedByStream = new ConcurrentHashMap<>();
    private final Map<String, ProgressSnapshot> lastProgressByStream = new ConcurrentHashMap<>();

    private record ProgressSnapshot(long timeMs, BigDecimal closePrice,
                                    BigDecimal quoteVolume, BigDecimal baseVolume, BigDecimal delta) {
    }

    /** legacy 이벤트가 남아 있는 동안에도 canonical source를 통해서만 완료봉을 만든다. */
    @EventListener
    public void onCandleCompleted(CandleCompletedEvent event) {
        long candleTimeMs = event.getCandle().getCandleTimeMs();
        broadcastClosedFromSource(event.getCandle().getSymbol(), SignalCandleSource.Interval.FIVE_MINUTES, candleTimeMs);
        long fifteenMs = Math.floorDiv(candleTimeMs, SignalCandleSource.Interval.FIFTEEN_MINUTES.durationMs())
                * SignalCandleSource.Interval.FIFTEEN_MINUTES.durationMs();
        broadcastClosedFromSource(event.getCandle().getSymbol(), SignalCandleSource.Interval.FIFTEEN_MINUTES, fifteenMs);
    }

    @EventListener
    public void onCandle1mCompleted(Candle1mCompletedEvent event) {
        broadcastClosedFromSource(event.getCandle().getSymbol(), SignalCandleSource.Interval.ONE_MINUTE,
                event.getCandle().getCandleTimeMs());
    }

    /** 이벤트가 더 이상 발생하지 않는 raw OFF 상태에서도 완료봉을 놓치지 않도록 5초마다 확인한다. */
    @Scheduled(fixedDelayString = "${binance.signal.candle.poll.fixed-delay-ms:5000}")
    public void broadcastClosedCandles() {
        pollClosed(SignalCandleSource.Interval.ONE_MINUTE);
        pollClosed(SignalCandleSource.Interval.FIVE_MINUTES);
        pollClosed(SignalCandleSource.Interval.FIFTEEN_MINUTES);
    }

    private void pollClosed(SignalCandleSource.Interval interval) {
        Set<String> symbols = candleWebSocketHandler.getActiveSymbols(interval.value());
        if (symbols.isEmpty()) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        for (String symbol : symbols) {
            if (symbol.isBlank()) {
                continue;
            }
            String streamKey = streamKey(symbol, interval);
            Long lastClosed = lastClosedByStream.get(streamKey);
            if (lastClosed == null) {
                initializeClosedWatermark(symbol, interval, streamKey, nowMs);
                continue;
            }
            long fromMs = lastClosed + interval.durationMs();
            try {
                candleSource.find(symbol, interval, Math.max(0, fromMs), nowMs,
                                SignalCandleSource.QueryMode.COMPLETED)
                        .stream()
                        .filter(c -> lastClosed == null || c.timeMs() > lastClosed)
                        .forEach(c -> broadcastClosed(symbol, interval, c));
            } catch (Exception e) {
                log.warn("[CandleStream] 완료봉 조회 실패 symbol={} interval={}: {}",
                        symbol, interval.value(), e.getMessage());
            }
        }
    }

    private void initializeClosedWatermark(String symbol, SignalCandleSource.Interval interval,
                                           String streamKey, long nowMs) {
        try {
            var recent = candleSource.find(symbol, interval,
                    Math.max(0, nowMs - interval.durationMs() * 2), nowMs,
                    SignalCandleSource.QueryMode.COMPLETED);
            recent.stream().mapToLong(SignalCandleSource.SignalCandle::timeMs).max()
                    .ifPresent(latest -> lastClosedByStream.putIfAbsent(streamKey, latest));
        } catch (Exception e) {
            log.warn("[CandleStream] 완료봉 watermark 초기화 실패 symbol={} interval={}: {}",
                    symbol, interval.value(), e.getMessage());
        }
    }

    /** 진행봉은 temp 1분 데이터를 5분/15분으로 부분 집계해 같은 open time으로 갱신한다. */
    @Scheduled(fixedDelayString = "${binance.signal.candle.progress-poll-fixed-delay-ms:10000}")
    public void broadcastInProgress5m() {
        pollInProgress(SignalCandleSource.Interval.FIVE_MINUTES);
    }

    @Scheduled(fixedDelayString = "${binance.signal.candle.progress-poll-fixed-delay-ms:10000}")
    public void broadcastInProgress15m() {
        pollInProgress(SignalCandleSource.Interval.FIFTEEN_MINUTES);
    }

    @Scheduled(fixedDelayString = "${binance.signal.candle.progress-poll-fixed-delay-ms:10000}")
    public void broadcastInProgress1m() {
        pollInProgress(SignalCandleSource.Interval.ONE_MINUTE);
    }

    private void pollInProgress(SignalCandleSource.Interval interval) {
        Set<String> symbols = candleWebSocketHandler.getActiveSymbols(interval.value());
        if (symbols.isEmpty()) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        long currentStartMs = Math.floorDiv(nowMs, interval.durationMs()) * interval.durationMs();
        for (String symbol : symbols) {
            if (symbol.isBlank()) {
                continue;
            }
            try {
                var candles = candleSource.find(symbol, interval, currentStartMs, nowMs,
                        SignalCandleSource.QueryMode.IN_PROGRESS);
                if (candles.isEmpty()) {
                    continue;
                }
                broadcastInProgress(symbol, interval, candles.get(candles.size() - 1));
            } catch (Exception e) {
                log.warn("[CandleStream] 진행봉 조회 실패 symbol={} interval={}: {}",
                        symbol, interval.value(), e.getMessage());
            }
        }
    }

    private void broadcastClosedFromSource(String symbol, SignalCandleSource.Interval interval, long candleTimeMs) {
        try {
            candleSource.find(symbol, interval, candleTimeMs, candleTimeMs + interval.durationMs(),
                            SignalCandleSource.QueryMode.COMPLETED)
                    .stream()
                    .filter(c -> c.timeMs() == candleTimeMs)
                    .forEach(c -> broadcastClosed(symbol, interval, c));
        } catch (Exception e) {
            log.warn("[CandleStream] 완료봉 원천 조회 실패 symbol={} interval={}: {}",
                    symbol, interval.value(), e.getMessage());
        }
    }

    private void broadcastClosed(String symbol, SignalCandleSource.Interval interval,
                                 SignalCandleSource.SignalCandle candle) {
        String streamKey = streamKey(symbol, interval);
        synchronized (lastClosedByStream) {
            Long lastClosed = lastClosedByStream.get(streamKey);
            if (lastClosed != null && candle.timeMs() <= lastClosed) {
                return;
            }
            try {
                candleWebSocketHandler.broadcastCandle(symbol, interval.value(),
                        objectMapper.writeValueAsString(toCandleMessage(candle, true)));
                lastClosedByStream.put(streamKey, candle.timeMs());
            } catch (JsonProcessingException e) {
                log.warn("[CandleStream] 완료봉 직렬화 실패 symbol={} interval={}: {}",
                        symbol, interval.value(), e.getMessage());
            }
        }
    }

    private void broadcastInProgress(String symbol, SignalCandleSource.Interval interval,
                                     SignalCandleSource.SignalCandle candle) {
        try {
            String json = objectMapper.writeValueAsString(toCandleMessage(candle, false));
            String streamKey = streamKey(symbol, interval);
            synchronized (lastProgressByStream) {
                ProgressSnapshot previous = lastProgressByStream.get(streamKey);
                if (previous != null && previous.timeMs() == candle.timeMs()
                        && same(previous.closePrice(), candle.closePrice())
                        && same(previous.quoteVolume(), candle.quoteVolume())
                        && same(previous.baseVolume(), candle.baseVolume())
                        && same(previous.delta(), candle.delta())) {
                    return;
                }
                candleWebSocketHandler.broadcastCandle(symbol, interval.value(), json);
                lastProgressByStream.put(streamKey, new ProgressSnapshot(
                        candle.timeMs(), candle.closePrice(), candle.quoteVolume(), candle.baseVolume(), candle.delta()));
            }
        } catch (JsonProcessingException e) {
            log.warn("[CandleStream] 진행봉 직렬화 실패 symbol={} interval={}: {}",
                    symbol, interval.value(), e.getMessage());
        }
    }

    private boolean same(BigDecimal first, BigDecimal second) {
        return first.compareTo(second) == 0;
    }

    private Map<String, Object> toCandleMessage(SignalCandleSource.SignalCandle candle, boolean closed) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("time", Instant.ofEpochMilli(candle.timeMs()).toString());
        msg.put("open", candle.openPrice().doubleValue());
        msg.put("high", candle.highPrice().doubleValue());
        msg.put("low", candle.lowPrice().doubleValue());
        msg.put("close", candle.closePrice().doubleValue());
        msg.put("volume", candle.baseVolume().doubleValue());
        msg.put("delta", candle.delta().doubleValue());
        msg.put("is_closed", closed);
        return msg;
    }

    private String streamKey(String symbol, SignalCandleSource.Interval interval) {
        return symbol + "|" + interval.value();
    }
}
