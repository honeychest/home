// [AGENT] 역할: 1분봉·5분봉 완성 이벤트 수신 → WS 브로드캐스트 + 진행 중 봉 주기 브로드캐스트
// 연관파일: CandleWebSocketHandler.java, AggTradeRollupService.java(이벤트 발행), Candle1mCompletedEvent.java, CandleCompletedEvent.java
// 주요메서드: onCandleCompleted(5m), onCandle1mCompleted(1m), broadcastInProgress5m(15s), broadcastInProgress1m(5s)
package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.AggTrade1m;
import com.chs.springboot.domain.binance.model.AggTrade5m;
import com.chs.springboot.domain.binance.model.event.Candle1mCompletedEvent;
import com.chs.springboot.domain.binance.model.event.CandleCompletedEvent;
import com.chs.springboot.domain.binance.websocket.CandleWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class CandleStreamService {

    private final CandleWebSocketHandler candleWebSocketHandler;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @EventListener
    public void onCandleCompleted(CandleCompletedEvent event) {
        AggTrade5m c = event.getCandle();
        try {
            Map<String, Object> msg = new HashMap<>();
            msg.put("time",      Instant.ofEpochMilli(c.getCandleTimeMs()).toString());
            msg.put("open",      c.getOpenPrice().doubleValue());
            msg.put("high",      c.getHighPrice().doubleValue());
            msg.put("low",       c.getLowPrice().doubleValue());
            msg.put("close",     c.getClosePrice().doubleValue());
            msg.put("volume",    c.getBuyQuantity().add(c.getSellQuantity()).doubleValue());
            msg.put("delta",     c.getDelta().doubleValue());
            msg.put("is_closed", true);
            candleWebSocketHandler.broadcastCandle(c.getSymbol(), "5m", objectMapper.writeValueAsString(msg));
        } catch (Exception e) {
            log.error("[CandleStream] 5분봉 브로드캐스트 실패: {}", e.getMessage());
        }
    }

    @EventListener
    public void onCandle1mCompleted(Candle1mCompletedEvent event) {
        AggTrade1m c = event.getCandle();
        try {
            Map<String, Object> msg = new HashMap<>();
            msg.put("time",      Instant.ofEpochMilli(c.getCandleTimeMs()).toString());
            msg.put("open",      c.getOpenPrice().doubleValue());
            msg.put("high",      c.getHighPrice().doubleValue());
            msg.put("low",       c.getLowPrice().doubleValue());
            msg.put("close",     c.getClosePrice().doubleValue());
            msg.put("volume",    c.getBuyQuantity().add(c.getSellQuantity()).doubleValue());
            msg.put("delta",     c.getDelta().doubleValue());
            msg.put("is_closed", true);
            candleWebSocketHandler.broadcastCandle(c.getSymbol(), "1m", objectMapper.writeValueAsString(msg));
        } catch (Exception e) {
            log.error("[CandleStream] 1분봉 브로드캐스트 실패: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void broadcastInProgress5m() {
        Set<String> symbols = candleWebSocketHandler.getActiveSymbols("5m");
        if (symbols.isEmpty()) return;

        long nowMs         = System.currentTimeMillis();
        long current5mStart = (nowMs / 300_000L) * 300_000L;

        for (String symbol : symbols) {
            if (symbol.isBlank()) continue;
            try {
                String sql = """
                    SELECT
                        SUBSTRING_INDEX(MIN(CONCAT(LPAD(candle_time_ms,20,'0'),'|',open_price)),'|',-1)  AS open_price,
                        MAX(high_price)                                                                   AS high_price,
                        MIN(low_price)                                                                    AS low_price,
                        SUBSTRING_INDEX(MAX(CONCAT(LPAD(candle_time_ms,20,'0'),'|',close_price)),'|',-1) AS close_price,
                        COALESCE(SUM(buy_quantity) + SUM(sell_quantity), 0)                              AS total_volume,
                        COALESCE(SUM(delta), 0)                                                          AS delta
                    FROM agg_trade_1s
                    WHERE symbol = ? AND market_type = 'FUTURES' AND candle_time_ms >= ? AND candle_time_ms < ?
                      AND trade_count > 0
                    """;
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, symbol, current5mStart, nowMs);
                if (rows.isEmpty() || rows.get(0).get("open_price") == null) continue;

                Map<String, Object> row = rows.get(0);
                Map<String, Object> msg = new HashMap<>();
                msg.put("time",      Instant.ofEpochMilli(current5mStart).toString());
                msg.put("open",      toBd(row.get("open_price")).doubleValue());
                msg.put("high",      toBd(row.get("high_price")).doubleValue());
                msg.put("low",       toBd(row.get("low_price")).doubleValue());
                msg.put("close",     toBd(row.get("close_price")).doubleValue());
                msg.put("volume",    toBd(row.get("total_volume")).doubleValue());
                msg.put("delta",     toBd(row.get("delta")).doubleValue());
                msg.put("is_closed", false);
                candleWebSocketHandler.broadcastCandle(symbol, "5m", objectMapper.writeValueAsString(msg));
            } catch (Exception e) {
                log.error("[CandleStream] 5분봉 진행중봉 브로드캐스트 실패 symbol={}: {}", symbol, e.getMessage());
            }
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void broadcastInProgress1m() {
        Set<String> symbols = candleWebSocketHandler.getActiveSymbols("1m");
        if (symbols.isEmpty()) return;

        long nowMs          = System.currentTimeMillis();
        long current1mStart = (nowMs / 60_000L) * 60_000L;

        for (String symbol : symbols) {
            if (symbol.isBlank()) continue;
            try {
                String sql = """
                    SELECT
                        SUBSTRING_INDEX(MIN(CONCAT(LPAD(candle_time_ms,20,'0'),'|',open_price)),'|',-1)  AS open_price,
                        MAX(high_price)                                                                   AS high_price,
                        MIN(low_price)                                                                    AS low_price,
                        SUBSTRING_INDEX(MAX(CONCAT(LPAD(candle_time_ms,20,'0'),'|',close_price)),'|',-1) AS close_price,
                        COALESCE(SUM(buy_quantity) + SUM(sell_quantity), 0)                              AS total_volume,
                        COALESCE(SUM(delta), 0)                                                          AS delta
                    FROM agg_trade_1s
                    WHERE symbol = ? AND market_type = 'FUTURES' AND candle_time_ms >= ? AND candle_time_ms < ?
                      AND trade_count > 0
                    """;
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, symbol, current1mStart, nowMs);
                if (rows.isEmpty() || rows.get(0).get("open_price") == null) continue;

                Map<String, Object> row = rows.get(0);
                Map<String, Object> msg = new HashMap<>();
                msg.put("time",      Instant.ofEpochMilli(current1mStart).toString());
                msg.put("open",      toBd(row.get("open_price")).doubleValue());
                msg.put("high",      toBd(row.get("high_price")).doubleValue());
                msg.put("low",       toBd(row.get("low_price")).doubleValue());
                msg.put("close",     toBd(row.get("close_price")).doubleValue());
                msg.put("volume",    toBd(row.get("total_volume")).doubleValue());
                msg.put("delta",     toBd(row.get("delta")).doubleValue());
                msg.put("is_closed", false);
                candleWebSocketHandler.broadcastCandle(symbol, "1m", objectMapper.writeValueAsString(msg));
            } catch (Exception e) {
                log.error("[CandleStream] 1분봉 진행중봉 브로드캐스트 실패 symbol={}: {}", symbol, e.getMessage());
            }
        }
    }

    private BigDecimal toBd(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal bd) return bd;
        return new BigDecimal(v.toString());
    }
}
