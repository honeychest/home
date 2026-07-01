// [AGENT] 역할: 1초봉 롤업 서비스 (raw_agg_trade → agg_trade_1s) | 연관파일: AggTrade1s.java, AggTrade1sRepository.java, AggTradeCollectStatusRepository.java, LeaderElectionService.java
// 실시간: @Scheduled(fixedRate=1000ms) — isLeader() 체크, T-2s~T-1s 구간 raw 집계 후 bad/empty row 교체 upsert
// 재발방지: raw 기반 1s candle은 기존 empty/id-zero row를 교체, 정상 raw row는 유지
// 백필: @PostConstruct 비동기 — Redis 분산락(30분 TTL + heartbeat) + 1시간 청크 단위 루프
package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.AggTrade1s;
import com.chs.springboot.domain.binance.model.AggTradeCollectStatus;
import com.chs.springboot.domain.binance.repository.AggTrade1sRepository;
import com.chs.springboot.domain.binance.repository.AggTradeCollectStatusRepository;
import com.chs.springboot.global.chs;
import com.chs.springboot.global.monitor.health.HealthCheckCatalog;
import com.chs.springboot.global.monitor.health.HealthHeartbeat;
import com.chs.springboot.global.redis.LeaderElectionService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AggTrade1sRollupService {

    private static final String CATCHUP_LOCK_KEY = "aggtrade:1s:rollup:catchup:lock";

    private static final String HK_ROLLUP_1S = HealthCheckCatalog.PIPE_ROLLUP_1S.key();
    private static final String HK_EMPTY_FIX = HealthCheckCatalog.PIPE_EMPTY_CANDLE_FIX.key();

    private final LeaderElectionService leaderElectionService;
    private final AggTrade1sRepository agg1sRepository;
    private final AggTradeCollectStatusRepository statusRepository;
    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate batchJdbcTemplate;
    private final HealthHeartbeat healthHeartbeat;

    private volatile CompletableFuture<Void> catchUpFuture;

    public CompletableFuture<Void> getCatchUpFuture() {
        return catchUpFuture != null ? catchUpFuture : CompletableFuture.completedFuture(null);
    }

    // ─── 실시간 롤업 (@Scheduled fixedRate=1000ms) ────────────────────────

    @Scheduled(fixedRate = 1000)
    public void rollup1s() {
        if (!leaderElectionService.isLeader()) return;

        long endMs   = (System.currentTimeMillis() / 1000) * 1000 - 1000;
        long startMs = endMs - 1000;

        Map<String, CandleData> aggregated = aggregate1sRaw(startMs, endMs);

        List<AggTradeCollectStatus> targets = statusRepository.findByEnabledTrue();
        for (AggTradeCollectStatus t : targets) {
            String key = t.getSymbol() + "|" + t.getMarketType();
            CandleData data = aggregated.get(key);

            if (data != null) {
                AggTrade1s candle = buildCandle(t.getSymbol(), t.getMarketType(), startMs, data);
                batchInsert(List.of(candle));
            } else {
                agg1sRepository.findLatestClosePriceBefore(t.getSymbol(), t.getMarketType(), endMs)
                    .ifPresent(close -> {
                        AggTrade1s empty = buildEmptyCandle(t.getSymbol(), t.getMarketType(), startMs, close);
                        agg1sRepository.insertIgnoreDuplicate(empty);
                    });
                // close 없으면 skip
            }
        }
        healthHeartbeat.beat(HK_ROLLUP_1S);
    }

    // ─── 주기적 빈 캔들 교정 (@Scheduled fixedRate=5분) ──────────────────
    // WebSocket 재연결 공백 구간에 생성된 빈 캔들을 실제 데이터로 교체

    @Scheduled(fixedRate = 300_000)
    public void correctRecentEmptyCandles() {
        if (!leaderElectionService.isLeader()) return;

        long nowMs  = (System.currentTimeMillis() / 1000) * 1000;
        long fromMs = nowMs - 15 * 60 * 1000L; // 최근 15분
        long toMs   = nowMs - 2_000L;          // rollup1s 실행 구간과 겹치지 않게 2초 여유

        List<AggTradeCollectStatus> targets = statusRepository.findByEnabledTrue();
        for (AggTradeCollectStatus t : targets) {
            Map<Long, CandleData> rawMap =
                aggregateChunkRaw(t.getSymbol(), t.getMarketType(), fromMs, toMs);

            if (rawMap.isEmpty()) continue;

            List<AggTrade1s> toCorrect = rawMap.entrySet().stream()
                .map(e -> buildCandle(t.getSymbol(), t.getMarketType(), e.getKey(), e.getValue()))
                .toList();

            batchInsert(toCorrect);
            log.info("[AggTrade1sCorrect] {} {} {}건 교정",
                t.getSymbol(), t.getMarketType(), toCorrect.size());
        }
        healthHeartbeat.beat(HK_EMPTY_FIX);
    }

    // ─── 백필 (@PostConstruct 비동기) ─────────────────────────────────────

    @PostConstruct
    public void catchUp() {
        catchUpFuture = CompletableFuture.runAsync(this::runCatchUp);
    }

    private void runCatchUp() {
        boolean locked = Boolean.TRUE.equals(
            redisTemplate.opsForValue().setIfAbsent(CATCHUP_LOCK_KEY, "locked", Duration.ofMinutes(30))
        );
        if (!locked) {
            log.info("[AggTrade1sRollup] 백필 락 획득 실패, skip");
            return;
        }

        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(
            new CustomizableThreadFactory("agg1s-backfill-heartbeat-"));
        heartbeat.scheduleAtFixedRate(() -> {
            try {
                redisTemplate.expire(CATCHUP_LOCK_KEY, Duration.ofMinutes(30));
            } catch (Exception e) {
                log.warn("[AggTrade1sRollup] heartbeat 실패: {}", e.getMessage());
            }
        }, 5, 5, TimeUnit.MINUTES);

        try {
            List<AggTradeCollectStatus> targets = statusRepository.findByEnabledTrue();
            for (AggTradeCollectStatus t : targets) {
                try {
                    runCatchUpForSymbol(t.getSymbol(), t.getMarketType());
                } catch (Exception e) {
                    log.error("[AggTrade1sRollup] 백필 실패 {} {}: {}", t.getSymbol(), t.getMarketType(), e.getMessage(), e);
                }
            }
            log.info("[AggTrade1sRollup] 백필 완료");
        } finally {
            heartbeat.shutdown();
            redisTemplate.delete(CATCHUP_LOCK_KEY);
        }
    }

    private void runCatchUpForSymbol(String symbol, String marketType) {
        // a. 마지막 1초봉 시각 조회
        Long lastMs = agg1sRepository
            .findMaxCandleTimeMsBySymbolAndMarketType(symbol, marketType)
            .orElse(null);

        if (lastMs == null) {
            Long firstTradedAt = batchJdbcTemplate.queryForObject(
                "SELECT MIN(traded_at) FROM raw_agg_trade WHERE symbol = ? AND market_type = ?",
                Long.class, symbol, marketType);
            if (firstTradedAt == null) {
                log.info("[AggTrade1sRollup] {} {} raw 데이터 없음, skip", symbol, marketType);
                return;
            }
            lastMs = (firstTradedAt / 1000) * 1000;
        }

        // b. targetEnd
        long targetEnd = (System.currentTimeMillis() / 1000) * 1000 - 2000;
        if (lastMs >= targetEnd) return;

        long chunkStart = lastMs;

        while (chunkStart < targetEnd) {
            long chunkEnd = Math.min(chunkStart + 3_600_000L, targetEnd);

            // ① lastClosePrice (null 가능 — 최초 청크이고 이전 데이터 없을 때)
            BigDecimal lastClosePrice = agg1sRepository
                .findLatestClosePriceBefore(symbol, marketType, chunkStart)
                .orElse(null);

            // ② raw 집계 → Map<candle_time_ms, CandleData>
            Map<Long, CandleData> rawMap = aggregateChunkRaw(symbol, marketType, chunkStart, chunkEnd);

            // ③ 기삽입 Set
            Set<Long> existingSet = new HashSet<>(
                agg1sRepository.findAllCandleTimeMsBySymbolAndMarketTypeAndRange(symbol, marketType, chunkStart, chunkEnd)
            );

            // ④ 단일 루프
            List<AggTrade1s> toInsert = new ArrayList<>();
            for (long t = chunkStart; t < chunkEnd; t += 1000) {
                CandleData data = rawMap.get(t);
                boolean exists = existingSet.contains(t);

                if (data != null) {
                    lastClosePrice = data.closePrice();
                    // 실제 거래 데이터가 있으면 항상 upsert
                    // (기존 빈 캔들(trade_count=0)이 있어도 실제 데이터로 교체)
                    toInsert.add(buildCandle(symbol, marketType, t, data));
                } else {
                    if (lastClosePrice == null) continue; // 이전 데이터 없으면 skip
                    if (!exists) {
                        // 빈 캔들은 기존 행이 없을 때만 삽입 (실제 데이터 덮어쓰기 방지)
                        toInsert.add(buildEmptyCandle(symbol, marketType, t, lastClosePrice));
                    }
                }
            }

            if (!toInsert.isEmpty()) {
                batchInsert(toInsert);
                log.info("[AggTrade1sRollup] {} {} 청크 {}~{} {}건 삽입", symbol, marketType, chunkStart, chunkEnd, toInsert.size());
            }

            chunkStart = chunkEnd;
        }
    }

    // ─── 집계 쿼리 ────────────────────────────────────────────────────────

    /** 실시간용: 전체 심볼 한번에 집계, Map key = "symbol|marketType" */
    private Map<String, CandleData> aggregate1sRaw(long startMs, long endMs) {
        String sql = """
            SELECT
                symbol,
                market_type,
                SUBSTRING_INDEX(GROUP_CONCAT(price ORDER BY agg_trade_id SEPARATOR '|'), '|', 1)      AS open_price,
                MAX(price)                                                                              AS high_price,
                MIN(price)                                                                              AS low_price,
                SUBSTRING_INDEX(GROUP_CONCAT(price ORDER BY agg_trade_id DESC SEPARATOR '|'), '|', 1) AS close_price,
                SUM(quantity * price)                                                                   AS total_volume,
                SUM(quantity)                                                                           AS total_qty,
                SUM(CASE WHEN is_buyer_maker = 0 THEN quantity * price ELSE 0 END)                     AS buy_volume,
                SUM(CASE WHEN is_buyer_maker = 1 THEN quantity * price ELSE 0 END)                     AS sell_volume,
                SUM(CASE WHEN is_buyer_maker = 0 THEN quantity ELSE 0 END)                             AS buy_quantity,
                SUM(CASE WHEN is_buyer_maker = 1 THEN quantity ELSE 0 END)                             AS sell_quantity,
                COUNT(CASE WHEN is_buyer_maker = 0 THEN 1 END)                                         AS buy_trade_count,
                COUNT(CASE WHEN is_buyer_maker = 1 THEN 1 END)                                         AS sell_trade_count,
                COUNT(*)                                                                                AS trade_count,
                MIN(agg_trade_id)                                                                       AS min_agg_trade_id,
                MAX(agg_trade_id)                                                                       AS max_agg_trade_id,
                MIN(first_trade_id)                                                                     AS min_first_trade_id,
                MAX(last_trade_id)                                                                      AS max_last_trade_id
            FROM raw_agg_trade
            WHERE traded_at >= ? AND traded_at < ?
            GROUP BY symbol, market_type, FLOOR(traded_at / 1000) * 1000
            """;

        List<Map<String, Object>> rows = batchJdbcTemplate.queryForList(sql, startMs, endMs);
        Map<String, CandleData> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String key = row.get("symbol") + "|" + row.get("market_type");
            result.put(key, toCandleData(row));
        }
        return result;
    }

    /** 백필용: 특정 심볼+마켓 청크 집계, Map key = candle_time_ms */
    private Map<Long, CandleData> aggregateChunkRaw(String symbol, String marketType, long startMs, long endMs) {
        String sql = """
            SELECT
                FLOOR(traded_at / 1000) * 1000                                                         AS candle_time_ms,
                SUBSTRING_INDEX(GROUP_CONCAT(price ORDER BY agg_trade_id SEPARATOR '|'), '|', 1)       AS open_price,
                MAX(price)                                                                              AS high_price,
                MIN(price)                                                                              AS low_price,
                SUBSTRING_INDEX(GROUP_CONCAT(price ORDER BY agg_trade_id DESC SEPARATOR '|'), '|', 1)  AS close_price,
                SUM(quantity * price)                                                                   AS total_volume,
                SUM(quantity)                                                                           AS total_qty,
                SUM(CASE WHEN is_buyer_maker = 0 THEN quantity * price ELSE 0 END)                     AS buy_volume,
                SUM(CASE WHEN is_buyer_maker = 1 THEN quantity * price ELSE 0 END)                     AS sell_volume,
                SUM(CASE WHEN is_buyer_maker = 0 THEN quantity ELSE 0 END)                             AS buy_quantity,
                SUM(CASE WHEN is_buyer_maker = 1 THEN quantity ELSE 0 END)                             AS sell_quantity,
                COUNT(CASE WHEN is_buyer_maker = 0 THEN 1 END)                                         AS buy_trade_count,
                COUNT(CASE WHEN is_buyer_maker = 1 THEN 1 END)                                         AS sell_trade_count,
                COUNT(*)                                                                                AS trade_count,
                MIN(agg_trade_id)                                                                       AS min_agg_trade_id,
                MAX(agg_trade_id)                                                                       AS max_agg_trade_id,
                MIN(first_trade_id)                                                                     AS min_first_trade_id,
                MAX(last_trade_id)                                                                      AS max_last_trade_id
            FROM raw_agg_trade
            WHERE symbol = ? AND market_type = ? AND traded_at >= ? AND traded_at < ?
            GROUP BY FLOOR(traded_at / 1000) * 1000
            """;

        List<Map<String, Object>> rows = batchJdbcTemplate.queryForList(sql, symbol, marketType, startMs, endMs);
        Map<Long, CandleData> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            long candleTimeMs = ((Number) row.get("candle_time_ms")).longValue();
            result.put(candleTimeMs, toCandleData(row));
        }
        return result;
    }

    // ─── 봉 생성 ──────────────────────────────────────────────────────────

    private AggTrade1s buildCandle(String symbol, String marketType, long candleTimeMs, CandleData d) {
        AggTrade1s c = new AggTrade1s();
        c.setSymbol(symbol);
        c.setMarketType(marketType);
        c.setCandleTimeMs(candleTimeMs);
        c.setOpenPrice(d.openPrice());
        c.setHighPrice(d.highPrice());
        c.setLowPrice(d.lowPrice());
        c.setClosePrice(d.closePrice());
        c.setVwap(d.vwap());
        c.setBuyVolume(d.buyVolume());
        c.setSellVolume(d.sellVolume());
        c.setTotalVolume(d.totalVolume());
        c.setBuyQuantity(d.buyQuantity());
        c.setSellQuantity(d.sellQuantity());
        c.setDelta(d.buyQuantity().subtract(d.sellQuantity()));
        c.setBuyTradeCount(d.buyTradeCount());
        c.setSellTradeCount(d.sellTradeCount());
        c.setTradeCount(d.tradeCount());
        c.setMinAggTradeId(d.minAggTradeId());
        c.setMaxAggTradeId(d.maxAggTradeId());
        c.setMinFirstTradeId(d.minFirstTradeId());
        c.setMaxLastTradeId(d.maxLastTradeId());
        return c;
    }

    private AggTrade1s buildEmptyCandle(String symbol, String marketType, long candleTimeMs, BigDecimal lastClose) {
        AggTrade1s c = new AggTrade1s();
        c.setSymbol(symbol);
        c.setMarketType(marketType);
        c.setCandleTimeMs(candleTimeMs);
        c.setOpenPrice(lastClose);
        c.setHighPrice(lastClose);
        c.setLowPrice(lastClose);
        c.setClosePrice(lastClose);
        c.setVwap(BigDecimal.ZERO);
        c.setBuyVolume(BigDecimal.ZERO);
        c.setSellVolume(BigDecimal.ZERO);
        c.setTotalVolume(BigDecimal.ZERO);
        c.setBuyQuantity(BigDecimal.ZERO);
        c.setSellQuantity(BigDecimal.ZERO);
        c.setDelta(BigDecimal.ZERO);
        c.setBuyTradeCount(0L);
        c.setSellTradeCount(0L);
        c.setTradeCount(0L);
        c.setMinAggTradeId(0L);
        c.setMaxAggTradeId(0L);
        c.setMinFirstTradeId(0L);
        c.setMaxLastTradeId(0L);
        return c;
    }

    // ─── 배치 INSERT ──────────────────────────────────────────────────────

    private void batchInsert(List<AggTrade1s> candles) {
        List<AggTrade1s> validCandles = candles.stream()
            .filter(c -> {
                boolean invalid = hasVolumeAndTradesWithoutDeltaSource(c);
                if (invalid) {
                    log.warn("[AggTrade1sGuard] skip invalid 1s candle symbol={} market={} candleTimeMs={} tradeCount={} totalVolume={}",
                        c.getSymbol(), c.getMarketType(), c.getCandleTimeMs(), c.getTradeCount(), c.getTotalVolume());
                }
                return !invalid;
            })
            .toList();
        if (validCandles.isEmpty()) {
            return;
        }

        String sql = """
            INSERT INTO agg_trade_1s
                (symbol, market_type, candle_time_ms,
                 open_price, high_price, low_price, close_price, vwap,
                 buy_volume, sell_volume, total_volume,
                 buy_quantity, sell_quantity, delta,
                 buy_trade_count, sell_trade_count, trade_count,
                 min_agg_trade_id, max_agg_trade_id,
                 min_first_trade_id, max_last_trade_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                open_price         = IF(trade_count = 0 OR min_agg_trade_id = 0 OR min_first_trade_id = 0, VALUES(open_price),         open_price),
                high_price         = IF(trade_count = 0 OR min_agg_trade_id = 0 OR min_first_trade_id = 0, VALUES(high_price),         high_price),
                low_price          = IF(trade_count = 0 OR min_agg_trade_id = 0 OR min_first_trade_id = 0, VALUES(low_price),          low_price),
                close_price        = IF(trade_count = 0 OR min_agg_trade_id = 0 OR min_first_trade_id = 0, VALUES(close_price),        close_price),
                vwap               = IF(trade_count = 0 OR min_agg_trade_id = 0 OR min_first_trade_id = 0, VALUES(vwap),               vwap),
                buy_volume         = IF(trade_count = 0 OR min_agg_trade_id = 0 OR min_first_trade_id = 0, VALUES(buy_volume),         buy_volume),
                sell_volume        = IF(trade_count = 0 OR min_agg_trade_id = 0 OR min_first_trade_id = 0, VALUES(sell_volume),        sell_volume),
                total_volume       = IF(trade_count = 0 OR min_agg_trade_id = 0 OR min_first_trade_id = 0, VALUES(total_volume),       total_volume),
                buy_quantity       = IF(trade_count = 0 OR min_agg_trade_id = 0 OR min_first_trade_id = 0, VALUES(buy_quantity),       buy_quantity),
                sell_quantity      = IF(trade_count = 0 OR min_agg_trade_id = 0 OR min_first_trade_id = 0, VALUES(sell_quantity),      sell_quantity),
                delta              = IF(trade_count = 0 OR min_agg_trade_id = 0 OR min_first_trade_id = 0, VALUES(delta),              delta),
                buy_trade_count    = IF(trade_count = 0 OR min_agg_trade_id = 0 OR min_first_trade_id = 0, VALUES(buy_trade_count),    buy_trade_count),
                sell_trade_count   = IF(trade_count = 0 OR min_agg_trade_id = 0 OR min_first_trade_id = 0, VALUES(sell_trade_count),   sell_trade_count),
                trade_count        = IF(trade_count = 0 OR min_agg_trade_id = 0 OR min_first_trade_id = 0, VALUES(trade_count),        trade_count),
                min_agg_trade_id   = IF(trade_count = 0 OR min_agg_trade_id = 0 OR min_first_trade_id = 0, VALUES(min_agg_trade_id),   min_agg_trade_id),
                max_agg_trade_id   = IF(trade_count = 0 OR min_agg_trade_id = 0 OR min_first_trade_id = 0, VALUES(max_agg_trade_id),   max_agg_trade_id),
                min_first_trade_id = IF(trade_count = 0 OR min_agg_trade_id = 0 OR min_first_trade_id = 0, VALUES(min_first_trade_id), min_first_trade_id),
                max_last_trade_id  = IF(trade_count = 0 OR min_agg_trade_id = 0 OR min_first_trade_id = 0, VALUES(max_last_trade_id),  max_last_trade_id)
            """;
        batchJdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                AggTrade1s c = validCandles.get(i);
                ps.setString(1, c.getSymbol());
                ps.setString(2, c.getMarketType());
                ps.setLong(3, c.getCandleTimeMs());
                ps.setBigDecimal(4, c.getOpenPrice());
                ps.setBigDecimal(5, c.getHighPrice());
                ps.setBigDecimal(6, c.getLowPrice());
                ps.setBigDecimal(7, c.getClosePrice());
                ps.setBigDecimal(8, c.getVwap());
                ps.setBigDecimal(9, c.getBuyVolume());
                ps.setBigDecimal(10, c.getSellVolume());
                ps.setBigDecimal(11, c.getTotalVolume());
                ps.setBigDecimal(12, c.getBuyQuantity());
                ps.setBigDecimal(13, c.getSellQuantity());
                ps.setBigDecimal(14, c.getDelta());
                ps.setLong(15, c.getBuyTradeCount());
                ps.setLong(16, c.getSellTradeCount());
                ps.setLong(17, c.getTradeCount());
                ps.setLong(18, c.getMinAggTradeId());
                ps.setLong(19, c.getMaxAggTradeId());
                ps.setLong(20, c.getMinFirstTradeId());
                ps.setLong(21, c.getMaxLastTradeId());
            }

            @Override
            public int getBatchSize() {
                return validCandles.size();
            }
        });
    }

    private boolean hasVolumeAndTradesWithoutDeltaSource(AggTrade1s c) {
        BigDecimal totalVolume = c.getTotalVolume() == null ? BigDecimal.ZERO : c.getTotalVolume();
        BigDecimal buyQuantity = c.getBuyQuantity() == null ? BigDecimal.ZERO : c.getBuyQuantity();
        BigDecimal sellQuantity = c.getSellQuantity() == null ? BigDecimal.ZERO : c.getSellQuantity();
        long tradeCount = c.getTradeCount() == null ? 0L : c.getTradeCount();
        return tradeCount > 0
            && totalVolume.compareTo(BigDecimal.ZERO) > 0
            && buyQuantity.add(sellQuantity).compareTo(BigDecimal.ZERO) == 0;
    }

    // ─── CandleData 변환 ──────────────────────────────────────────────────

    private CandleData toCandleData(Map<String, Object> row) {
        BigDecimal buyQty   = toBd(row.get("buy_quantity"));
        BigDecimal sellQty  = toBd(row.get("sell_quantity"));
        BigDecimal totalVol = toBd(row.get("total_volume"));
        BigDecimal totalQty = buyQty.add(sellQty);
        BigDecimal vwap = totalQty.compareTo(BigDecimal.ZERO) == 0
            ? BigDecimal.ZERO
            : totalVol.divide(totalQty, 8, RoundingMode.HALF_UP);

        return new CandleData(
            toBd(row.get("open_price")),
            toBd(row.get("high_price")),
            toBd(row.get("low_price")),
            toBd(row.get("close_price")),
            vwap,
            toBd(row.get("buy_volume")),
            toBd(row.get("sell_volume")),
            totalVol,
            buyQty,
            sellQty,
            toLong(row.get("buy_trade_count")),
            toLong(row.get("sell_trade_count")),
            toLong(row.get("trade_count")),
            toLong(row.get("min_agg_trade_id")),
            toLong(row.get("max_agg_trade_id")),
            toLong(row.get("min_first_trade_id")),
            toLong(row.get("max_last_trade_id"))
        );
    }

    private record CandleData(
        BigDecimal openPrice, BigDecimal highPrice, BigDecimal lowPrice, BigDecimal closePrice,
        BigDecimal vwap,
        BigDecimal buyVolume, BigDecimal sellVolume, BigDecimal totalVolume,
        BigDecimal buyQuantity, BigDecimal sellQuantity,
        long buyTradeCount, long sellTradeCount, long tradeCount,
        long minAggTradeId, long maxAggTradeId,
        long minFirstTradeId, long maxLastTradeId
    ) {}

    // ─── 유틸 ─────────────────────────────────────────────────────────────

    private BigDecimal toBd(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal bd) return bd;
        return new BigDecimal(v.toString());
    }

    private long toLong(Object v) {
        if (v == null) return 0L;
        return ((Number) v).longValue();
    }
}
