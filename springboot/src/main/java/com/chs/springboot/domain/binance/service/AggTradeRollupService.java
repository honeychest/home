// [AGENT] 1분봉·5분봉 롤업 스케줄러 — agg_trade_1s → agg_trade_1m / agg_trade_1m → agg_trade_5m
// 연관파일: AggTrade1m.java, AggTrade5m.java, AggTrade1mRepository.java, AggTrade5mRepository.java, AggTrade1sRollupService.java, CandleStreamService.java(CandleCompletedEvent 수신)
// 주요메서드: rollup1m() @Scheduled(10 * * * * *), rollup5m() @Scheduled(30 */5 * * * *)
// 재발방지: 기존 id-zero/kline-like 1m/5m row는 duplicate 시 raw/1s 기반 rollup 값으로 교체
// catchUp(): 1s catchUp 완료 후 체이닝 실행 (CompletableFuture)
package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.AggTrade1m;
import com.chs.springboot.domain.binance.model.AggTrade5m;
import com.chs.springboot.domain.binance.model.event.Candle1mCompletedEvent;
import com.chs.springboot.domain.binance.model.event.CandleCompletedEvent;
import com.chs.springboot.domain.binance.repository.AggTrade1mRepository;
import com.chs.springboot.domain.binance.repository.AggTrade5mRepository;
import com.chs.springboot.domain.binance.repository.AggTradeCollectStatusRepository;
import com.chs.springboot.global.chs;
import com.chs.springboot.global.monitor.health.HealthCheckCatalog;
import com.chs.springboot.global.monitor.health.HealthHeartbeat;
import com.chs.springboot.global.redis.LeaderElectionService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AggTradeRollupService {

    private static final String CATCHUP_LOCK_KEY = "aggtrade:rollup:catchup:lock";
    private static final String REPLACE_BAD_CANDLE_UPDATE_SQL = """
        open_price         = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(open_price),         open_price),
        high_price         = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(high_price),         high_price),
        low_price          = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(low_price),          low_price),
        close_price        = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(close_price),        close_price),
        vwap               = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(vwap),               vwap),
        buy_volume         = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(buy_volume),         buy_volume),
        sell_volume        = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(sell_volume),        sell_volume),
        total_volume       = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(total_volume),       total_volume),
        buy_quantity       = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(buy_quantity),       buy_quantity),
        sell_quantity      = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(sell_quantity),      sell_quantity),
        delta              = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(delta),              delta),
        buy_trade_count    = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(buy_trade_count),    buy_trade_count),
        sell_trade_count   = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(sell_trade_count),   sell_trade_count),
        trade_count        = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(trade_count),        trade_count),
        min_agg_trade_id   = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(min_agg_trade_id),   min_agg_trade_id),
        max_agg_trade_id   = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(max_agg_trade_id),   max_agg_trade_id),
        min_first_trade_id = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(min_first_trade_id), min_first_trade_id),
        max_last_trade_id  = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(max_last_trade_id),  max_last_trade_id)
        """;

    private final LeaderElectionService leaderElectionService;
    private final AggTrade1mRepository agg1mRepository;
    private final AggTrade5mRepository agg5mRepository;
    private final AggTradeCollectStatusRepository statusRepository;
    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate batchJdbcTemplate;
    private final AggTrade1sRollupService agg1sRollupService;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final HealthHeartbeat healthHeartbeat;

    private static final String HK_ROLLUP_1M = HealthCheckCatalog.PIPE_ROLLUP_1M.key();
    private static final String HK_ROLLUP_5M = HealthCheckCatalog.PIPE_ROLLUP_5M.key();

    // ─── 기동 시 catch-up (1s catchUp 완료 후 체이닝) ────────────────────

    @PostConstruct
    public void catchUp() {
        log.info("[RollupCatchUp] catchUp() 호출됨, 1s future 대기 시작");
        agg1sRollupService.getCatchUpFuture()
            .exceptionally(e -> {
                log.error("[RollupCatchUp] 1s catchUp 실패로 1m/5m catchUp 미실행: {}", e.getMessage(), e);
                return null;
            })
            .thenRunAsync(() -> {
                log.info("[RollupCatchUp] 1s future 완료, runCatchUp 진입");
                runCatchUp();
            });
    }

    private void runCatchUp() {
        boolean locked = Boolean.TRUE.equals(
            redisTemplate.opsForValue().setIfAbsent(CATCHUP_LOCK_KEY, "locked", Duration.ofMinutes(10))
        );
        if (!locked) {
            log.info("[RollupCatchUp] 다른 서버가 실행 중, skip");
            return;
        }
        try {
            var targets = statusRepository.findByEnabledTrue();
            long nowMs = currentMinuteStartMs(); // 진행 중인 분은 제외

            // 1m 먼저 전부 채운 뒤 5m 진행 (정합성)
            for (var t : targets) {
                catchUp1m(t.getSymbol(), t.getMarketType(), nowMs);
            }
            for (var t : targets) {
                catchUp5m(t.getSymbol(), t.getMarketType(), nowMs);
            }
            log.info("[RollupCatchUp] 완료");
        } catch (Exception e) {
            log.error("[RollupCatchUp] 실패: {}", e.getMessage(), e);
        } finally {
            redisTemplate.delete(CATCHUP_LOCK_KEY);
        }
    }

    private void catchUp1m(String symbol, String marketType, long nowMs) {
        Long lastMs = agg1mRepository
            .findMaxCandleTimeMsBySymbolAndMarketType(symbol, marketType)
            .orElse(null);
        long fromMs;
        if (lastMs == null) {
            Long firstMs = batchJdbcTemplate.queryForObject(
                "SELECT MIN(candle_time_ms) FROM agg_trade_1s WHERE symbol = ? AND market_type = ?",
                Long.class, symbol, marketType);
            Long lastAvailMs = batchJdbcTemplate.queryForObject(
                "SELECT MAX(candle_time_ms) FROM agg_trade_1s WHERE symbol = ? AND market_type = ?",
                Long.class, symbol, marketType);
            log.info("[RollupCatchUp] {} {} 1m lastMs=null, 1s범위={} ~ {}", symbol, marketType, firstMs, lastAvailMs);
            if (firstMs == null) {
                log.info("[RollupCatchUp] {} {} 1s 데이터 없음, skip", symbol, marketType);
                return;
            }
            fromMs = (firstMs / 60_000L) * 60_000L;
        } else {
            long lookbackMs = nowMs - 7 * 24 * 60 * 60 * 1000L; // 7일 내 갭만 탐지
            Long firstGapMs = batchJdbcTemplate.queryForObject(
                """
                SELECT MIN(FLOOR(s.candle_time_ms / 60000) * 60000)
                FROM agg_trade_1s s
                WHERE s.symbol = ? AND s.market_type = ?
                  AND s.candle_time_ms >= ?
                  AND NOT EXISTS (
                      SELECT 1 FROM agg_trade_1m m
                      WHERE m.symbol = s.symbol
                        AND m.market_type = s.market_type
                        AND m.candle_time_ms = FLOOR(s.candle_time_ms / 60000) * 60000
                  )
                """,
                Long.class, symbol, marketType, lookbackMs);
            long forwardFrom = lastMs + 60_000L;
            fromMs = (firstGapMs != null) ? firstGapMs : forwardFrom;
            log.info("[RollupCatchUp] {} {} 1m lastMs={}, firstGap={}, fromMs={}",
                symbol, marketType, lastMs, firstGapMs, fromMs);
        }
        if (fromMs >= nowMs) {
            log.info("[RollupCatchUp] {} {} 1m fromMs={} >= nowMs={}, skip", symbol, marketType, fromMs, nowMs);
            return;
        }

        log.info("[RollupCatchUp] {} {} 1m catch-up {} ~ {}", symbol, marketType, fromMs, nowMs);

        chs.dlog("catchUp1m raw 기반 1m rollup 결과 준비");
        chs.dlog("catchUp1m 기존 1m row의 min_agg_trade_id 또는 min_first_trade_id가 0인지 확인");
        chs.dlog("catchUp1m 기존 1m row가 buy_trade_count 0 sell_trade_count 0 trade_count 양수인지 확인");
        chs.dlog("catchUp1m 기존 1m row가 id-zero 또는 kline-like이면 raw 기반 rollup 값으로 교체");
        chs.dlog("catchUp1m 정상 raw 기반 기존 row는 유지");
        String sql = """
            INSERT INTO agg_trade_1m
                (symbol, market_type, candle_time_ms,
                 open_price, high_price, low_price, close_price, vwap,
                 buy_volume, sell_volume, total_volume,
                 buy_quantity, sell_quantity, delta,
                 buy_trade_count, sell_trade_count, trade_count,
                 min_agg_trade_id, max_agg_trade_id,
                 min_first_trade_id, max_last_trade_id)
            SELECT
                symbol, market_type,
                FLOOR(candle_time_ms / 60000) * 60000                                                          AS candle_time_ms,
                SUBSTRING_INDEX(MIN(CONCAT(LPAD(candle_time_ms,20,'0'),'|',open_price)),'|',-1)                AS open_price,
                MAX(high_price)                                                                                AS high_price,
                MIN(low_price)                                                                                 AS low_price,
                SUBSTRING_INDEX(MAX(CONCAT(LPAD(candle_time_ms,20,'0'),'|',close_price)),'|',-1)               AS close_price,
                CASE WHEN SUM(buy_quantity + sell_quantity) = 0 THEN 0
                     ELSE SUM(total_volume) / SUM(buy_quantity + sell_quantity) END                            AS vwap,
                SUM(buy_volume)                                                                                AS buy_volume,
                SUM(sell_volume)                                                                               AS sell_volume,
                SUM(total_volume)                                                                              AS total_volume,
                SUM(buy_quantity)                                                                              AS buy_quantity,
                SUM(sell_quantity)                                                                             AS sell_quantity,
                SUM(buy_quantity) - SUM(sell_quantity)                                                         AS delta,
                SUM(buy_trade_count)                                                                           AS buy_trade_count,
                SUM(sell_trade_count)                                                                          AS sell_trade_count,
                SUM(trade_count)                                                                               AS trade_count,
                MIN(min_agg_trade_id)                                                                          AS min_agg_trade_id,
                MAX(max_agg_trade_id)                                                                          AS max_agg_trade_id,
                MIN(min_first_trade_id)                                                                        AS min_first_trade_id,
                MAX(max_last_trade_id)                                                                         AS max_last_trade_id
            FROM agg_trade_1s
            WHERE symbol = ? AND market_type = ? AND candle_time_ms >= ? AND candle_time_ms < ?
            GROUP BY symbol, market_type, FLOOR(candle_time_ms / 60000) * 60000
            ON DUPLICATE KEY UPDATE
            """ + REPLACE_BAD_CANDLE_UPDATE_SQL;
        int rows = batchJdbcTemplate.update(sql, symbol, marketType, fromMs, nowMs);
        log.info("[RollupCatchUp] {} {} 1m {}건 삽입", symbol, marketType, rows);
    }

    private void catchUp5m(String symbol, String marketType, long nowMs) {
        Long lastMs = agg5mRepository
            .findMaxCandleTimeMsBySymbolAndMarketType(symbol, marketType)
            .orElse(null);
        long to5mMs = (nowMs / 300_000L) * 300_000L; // 미완료 구간 제외
        long fromMs;
        if (lastMs == null) {
            Long firstMs = batchJdbcTemplate.queryForObject(
                "SELECT MIN(candle_time_ms) FROM agg_trade_1m WHERE symbol = ? AND market_type = ?",
                Long.class, symbol, marketType);
            if (firstMs == null) {
                log.info("[RollupCatchUp] {} {} 1m 데이터 없음, skip", symbol, marketType);
                return;
            }
            fromMs = (firstMs / 300_000L) * 300_000L;
        } else {
            // 중간 갭 탐지: 7일 내 1m봉은 있지만 5m봉이 없는 최초 구간
            long lookbackMs = nowMs - 7 * 24 * 60 * 60 * 1000L;
            Long firstGapMs = batchJdbcTemplate.queryForObject(
                """
                SELECT MIN(FLOOR(m.candle_time_ms / 300000) * 300000)
                FROM agg_trade_1m m
                WHERE m.symbol = ? AND m.market_type = ?
                  AND m.candle_time_ms >= ?
                  AND NOT EXISTS (
                      SELECT 1 FROM agg_trade_5m f
                      WHERE f.symbol = m.symbol
                        AND f.market_type = m.market_type
                        AND f.candle_time_ms = FLOOR(m.candle_time_ms / 300000) * 300000
                  )
                """,
                Long.class, symbol, marketType, lookbackMs);
            long forwardFrom = lastMs + 300_000L;
            fromMs = (firstGapMs != null) ? firstGapMs : forwardFrom;
            log.info("[RollupCatchUp] {} {} 5m lastMs={}, firstGap={}, fromMs={}",
                symbol, marketType, lastMs, firstGapMs, fromMs);
        }
        if (fromMs >= to5mMs) return;

        log.info("[RollupCatchUp] {} {} 5m catch-up {} ~ {}", symbol, marketType, fromMs, to5mMs);

        String sql = """
            INSERT INTO agg_trade_5m
                (symbol, market_type, candle_time_ms,
                 open_price, high_price, low_price, close_price, vwap,
                 buy_volume, sell_volume, total_volume,
                 buy_quantity, sell_quantity, delta,
                 buy_trade_count, sell_trade_count, trade_count,
                 min_agg_trade_id, max_agg_trade_id,
                 min_first_trade_id, max_last_trade_id)
            SELECT
                symbol, market_type,
                FLOOR(candle_time_ms / 300000) * 300000                                                       AS candle_time_ms,
                SUBSTRING_INDEX(MIN(CONCAT(LPAD(candle_time_ms,20,'0'),'|',open_price)),'|',-1)               AS open_price,
                MAX(high_price)                                                                               AS high_price,
                MIN(low_price)                                                                                AS low_price,
                SUBSTRING_INDEX(MAX(CONCAT(LPAD(candle_time_ms,20,'0'),'|',close_price)),'|',-1)             AS close_price,
                CASE WHEN SUM(buy_quantity + sell_quantity) = 0 THEN 0
                     ELSE SUM(total_volume) / SUM(buy_quantity + sell_quantity) END                           AS vwap,
                SUM(buy_volume)                                                                               AS buy_volume,
                SUM(sell_volume)                                                                              AS sell_volume,
                SUM(total_volume)                                                                             AS total_volume,
                SUM(buy_quantity)                                                                             AS buy_quantity,
                SUM(sell_quantity)                                                                            AS sell_quantity,
                SUM(buy_quantity) - SUM(sell_quantity)                                                        AS delta,
                SUM(buy_trade_count)                                                                         AS buy_trade_count,
                SUM(sell_trade_count)                                                                        AS sell_trade_count,
                SUM(trade_count)                                                                             AS trade_count,
                MIN(min_agg_trade_id)                                                                        AS min_agg_trade_id,
                MAX(max_agg_trade_id)                                                                        AS max_agg_trade_id,
                MIN(min_first_trade_id)                                                                      AS min_first_trade_id,
                MAX(max_last_trade_id)                                                                       AS max_last_trade_id
            FROM agg_trade_1m
            WHERE symbol = ? AND market_type = ? AND candle_time_ms >= ? AND candle_time_ms < ?
            GROUP BY symbol, market_type, FLOOR(candle_time_ms / 300000) * 300000
            ON DUPLICATE KEY UPDATE
            """ + REPLACE_BAD_CANDLE_UPDATE_SQL;
        int rows = batchJdbcTemplate.update(sql, symbol, marketType, fromMs, to5mMs);
        log.info("[RollupCatchUp] {} {} 5m {}건 삽입", symbol, marketType, rows);
    }

    // ─── 수동 롤업 API (admin 트리거용) ─────────────────────────────────────

    public Map<String, Integer> rollupRange(long fromMs, long toMs) {
        boolean locked = Boolean.TRUE.equals(
            redisTemplate.opsForValue().setIfAbsent(CATCHUP_LOCK_KEY, "locked", Duration.ofMinutes(10))
        );
        if (!locked) {
            throw new IllegalStateException("다른 롤업이 실행 중입니다. 잠시 후 재시도해주세요.");
        }
        log.info("[RollupRange] 시작 fromMs={} toMs={}", fromMs, toMs);
        int total1m = 0;
        int total5m = 0;
        try {
            var targets = statusRepository.findByEnabledTrue();

            chs.dlog("rollupRange 1m raw 기반 rollup 결과 준비");
            chs.dlog("rollupRange 1m 기존 row가 id-zero 또는 kline-like이면 raw 기반 rollup 값으로 교체");
            chs.dlog("rollupRange 1m 정상 raw 기반 기존 row는 유지");
            String sql1m = """
                INSERT INTO agg_trade_1m
                    (symbol, market_type, candle_time_ms,
                     open_price, high_price, low_price, close_price, vwap,
                     buy_volume, sell_volume, total_volume,
                     buy_quantity, sell_quantity, delta,
                     buy_trade_count, sell_trade_count, trade_count,
                     min_agg_trade_id, max_agg_trade_id,
                     min_first_trade_id, max_last_trade_id)
                SELECT
                    symbol, market_type,
                    FLOOR(candle_time_ms / 60000) * 60000                                                          AS candle_time_ms,
                    SUBSTRING_INDEX(MIN(CONCAT(LPAD(candle_time_ms,20,'0'),'|',open_price)),'|',-1)                AS open_price,
                    MAX(high_price)                                                                                AS high_price,
                    MIN(low_price)                                                                                 AS low_price,
                    SUBSTRING_INDEX(MAX(CONCAT(LPAD(candle_time_ms,20,'0'),'|',close_price)),'|',-1)               AS close_price,
                    CASE WHEN SUM(buy_quantity + sell_quantity) = 0 THEN 0
                         ELSE SUM(total_volume) / SUM(buy_quantity + sell_quantity) END                            AS vwap,
                    SUM(buy_volume)                                                                                AS buy_volume,
                    SUM(sell_volume)                                                                               AS sell_volume,
                    SUM(total_volume)                                                                              AS total_volume,
                    SUM(buy_quantity)                                                                              AS buy_quantity,
                    SUM(sell_quantity)                                                                             AS sell_quantity,
                    SUM(buy_quantity) - SUM(sell_quantity)                                                         AS delta,
                    SUM(buy_trade_count)                                                                           AS buy_trade_count,
                    SUM(sell_trade_count)                                                                          AS sell_trade_count,
                    SUM(trade_count)                                                                               AS trade_count,
                    MIN(min_agg_trade_id)                                                                          AS min_agg_trade_id,
                    MAX(max_agg_trade_id)                                                                          AS max_agg_trade_id,
                    MIN(min_first_trade_id)                                                                        AS min_first_trade_id,
                    MAX(max_last_trade_id)                                                                         AS max_last_trade_id
                FROM agg_trade_1s
                WHERE symbol = ? AND market_type = ? AND candle_time_ms >= ? AND candle_time_ms < ?
                GROUP BY symbol, market_type, FLOOR(candle_time_ms / 60000) * 60000
                ON DUPLICATE KEY UPDATE
                """ + REPLACE_BAD_CANDLE_UPDATE_SQL;
            for (var t : targets) {
                int n = batchJdbcTemplate.update(sql1m, t.getSymbol(), t.getMarketType(), fromMs, toMs);
                total1m += n;
                log.info("[RollupRange] {} {} 1m {}건", t.getSymbol(), t.getMarketType(), n);
            }

            long from5m = (fromMs / 300_000L) * 300_000L;
            long to5m   = ((toMs + 299_999L) / 300_000L) * 300_000L;

            chs.dlog("rollupRange 5m 포함 1m row에 id-zero 또는 kline-like row가 있는지 확인");
            chs.dlog("rollupRange 5m 이상 1m 포함 구간이면 5m를 재집계 값으로 교체");
            chs.dlog("rollupRange 5m 정상 5m 기존 row는 유지");
            String sql5m = """
                INSERT INTO agg_trade_5m
                    (symbol, market_type, candle_time_ms,
                     open_price, high_price, low_price, close_price, vwap,
                     buy_volume, sell_volume, total_volume,
                     buy_quantity, sell_quantity, delta,
                     buy_trade_count, sell_trade_count, trade_count,
                     min_agg_trade_id, max_agg_trade_id,
                     min_first_trade_id, max_last_trade_id)
                SELECT
                    symbol, market_type,
                    FLOOR(candle_time_ms / 300000) * 300000                                                       AS candle_time_ms,
                    SUBSTRING_INDEX(MIN(CONCAT(LPAD(candle_time_ms,20,'0'),'|',open_price)),'|',-1)               AS open_price,
                    MAX(high_price)                                                                               AS high_price,
                    MIN(low_price)                                                                                AS low_price,
                    SUBSTRING_INDEX(MAX(CONCAT(LPAD(candle_time_ms,20,'0'),'|',close_price)),'|',-1)             AS close_price,
                    CASE WHEN SUM(buy_quantity + sell_quantity) = 0 THEN 0
                         ELSE SUM(total_volume) / SUM(buy_quantity + sell_quantity) END                           AS vwap,
                    SUM(buy_volume)                                                                               AS buy_volume,
                    SUM(sell_volume)                                                                              AS sell_volume,
                    SUM(total_volume)                                                                             AS total_volume,
                    SUM(buy_quantity)                                                                             AS buy_quantity,
                    SUM(sell_quantity)                                                                            AS sell_quantity,
                    SUM(buy_quantity) - SUM(sell_quantity)                                                        AS delta,
                    SUM(buy_trade_count)                                                                         AS buy_trade_count,
                    SUM(sell_trade_count)                                                                        AS sell_trade_count,
                    SUM(trade_count)                                                                             AS trade_count,
                    MIN(min_agg_trade_id)                                                                        AS min_agg_trade_id,
                    MAX(max_agg_trade_id)                                                                        AS max_agg_trade_id,
                    MIN(min_first_trade_id)                                                                      AS min_first_trade_id,
                    MAX(max_last_trade_id)                                                                       AS max_last_trade_id
                FROM agg_trade_1m
                WHERE symbol = ? AND market_type = ? AND candle_time_ms >= ? AND candle_time_ms < ?
                GROUP BY symbol, market_type, FLOOR(candle_time_ms / 300000) * 300000
                ON DUPLICATE KEY UPDATE
                """ + REPLACE_BAD_CANDLE_UPDATE_SQL;
            for (var t : targets) {
                int n = batchJdbcTemplate.update(sql5m, t.getSymbol(), t.getMarketType(), from5m, to5m);
                total5m += n;
                log.info("[RollupRange] {} {} 5m {}건", t.getSymbol(), t.getMarketType(), n);
            }
        } finally {
            redisTemplate.delete(CATCHUP_LOCK_KEY);
            log.info("[RollupRange] 완료 total1m={} total5m={}", total1m, total5m);
        }
        return Map.of("inserted1m", total1m, "inserted5m", total5m);
    }

    // ─── 1분봉 롤업 (매 분 :10초) — agg_trade_1s → agg_trade_1m ────────────

    @Scheduled(cron = "10 * * * * *")
    public void rollup1m() {
        if (!leaderElectionService.isLeader()) return;

        long endMs   = currentMinuteStartMs();
        long startMs = endMs - 60_000L;

        List<Map<String, Object>> rows = aggregateFrom1sCandles(startMs, endMs);
        for (Map<String, Object> row : rows) {
            AggTrade1m candle = new AggTrade1m();
            fill1mCandle(candle, row, startMs);
//            chs.dlog("rollup1m 기존 row가 id-zero 또는 kline-like이면 raw 기반 1m candle로 교체");
//            chs.dlog("rollup1m 정상 raw 기반 기존 row는 유지");
            upsert1mReplacingBad(candle);
            log.debug("[Rollup1m] {} {} 집계 완료", candle.getSymbol(), candle.getMarketType());
            eventPublisher.publishEvent(new Candle1mCompletedEvent(this, candle));
        }
        healthHeartbeat.beat(HK_ROLLUP_1M);
    }

    // ─── 5분봉 롤업 (매 5분 :30초) — agg_trade_1m → agg_trade_5m ───────────
    // :10초에 1분봉이 생성되므로 20초 여유를 두고 :30초에 실행

    @Scheduled(cron = "30 */5 * * * *")
    public void rollup5m() {
        if (!leaderElectionService.isLeader()) return;

        long endMs   = current5mStartMs();
        long startMs = endMs - 300_000L;

        List<Map<String, Object>> rows = aggregateFrom1mCandles(startMs, endMs);
        for (Map<String, Object> row : rows) {
            AggTrade5m candle = new AggTrade5m();
            fill5mCandle(candle, row);
//            chs.dlog("rollup5m 포함 1m row에 id-zero 또는 kline-like row가 있으면 5m candle 교체");
//            chs.dlog("rollup5m 정상 5m 기존 row는 유지");
            upsert5mReplacingBad(candle);
            log.debug("[Rollup5m] {} {} 집계 완료", candle.getSymbol(), candle.getMarketType());
            eventPublisher.publishEvent(new CandleCompletedEvent(this, candle));
        }
        healthHeartbeat.beat(HK_ROLLUP_5M);
    }

    private void upsert1mReplacingBad(AggTrade1m candle) {
        String sql = """
            INSERT INTO agg_trade_1m
                (symbol, market_type, candle_time_ms,
                 open_price, high_price, low_price, close_price, vwap,
                 buy_volume, sell_volume, total_volume,
                 buy_quantity, sell_quantity, delta,
                 buy_trade_count, sell_trade_count, trade_count,
                 min_agg_trade_id, max_agg_trade_id,
                 min_first_trade_id, max_last_trade_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
            """ + REPLACE_BAD_CANDLE_UPDATE_SQL;
        batchJdbcTemplate.update(sql, candle.getSymbol(), candle.getMarketType(), candle.getCandleTimeMs(),
            candle.getOpenPrice(), candle.getHighPrice(), candle.getLowPrice(), candle.getClosePrice(), candle.getVwap(),
            candle.getBuyVolume(), candle.getSellVolume(), candle.getTotalVolume(),
            candle.getBuyQuantity(), candle.getSellQuantity(), candle.getDelta(),
            candle.getBuyTradeCount(), candle.getSellTradeCount(), candle.getTradeCount(),
            candle.getMinAggTradeId(), candle.getMaxAggTradeId(), candle.getMinFirstTradeId(), candle.getMaxLastTradeId());
    }

    private void upsert5mReplacingBad(AggTrade5m candle) {
        String sql = """
            INSERT INTO agg_trade_5m
                (symbol, market_type, candle_time_ms,
                 open_price, high_price, low_price, close_price, vwap,
                 buy_volume, sell_volume, total_volume,
                 buy_quantity, sell_quantity, delta,
                 buy_trade_count, sell_trade_count, trade_count,
                 min_agg_trade_id, max_agg_trade_id,
                 min_first_trade_id, max_last_trade_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
            """ + REPLACE_BAD_CANDLE_UPDATE_SQL;
        batchJdbcTemplate.update(sql, candle.getSymbol(), candle.getMarketType(), candle.getCandleTimeMs(),
            candle.getOpenPrice(), candle.getHighPrice(), candle.getLowPrice(), candle.getClosePrice(), candle.getVwap(),
            candle.getBuyVolume(), candle.getSellVolume(), candle.getTotalVolume(),
            candle.getBuyQuantity(), candle.getSellQuantity(), candle.getDelta(),
            candle.getBuyTradeCount(), candle.getSellTradeCount(), candle.getTradeCount(),
            candle.getMinAggTradeId(), candle.getMaxAggTradeId(), candle.getMinFirstTradeId(), candle.getMaxLastTradeId());
    }

    // ─── 1m: agg_trade_1s 집계 ───────────────────────────────────────────

    // OHLC는 빈 캔들(trade_count=0, 이월 가격) 제외하고 실거래 1초봉만 사용.
    // 수집 지연 중 빈 캔들에 박힌 stale 가격이 시가/고가/저가/종가를 오염시키는 것 방지.
    // 분 전체에 실거래 1초봉이 하나도 없으면 기존 방식(빈 캔들 포함)으로 폴백 — 1분봉 누락 방지.
    private List<Map<String, Object>> aggregateFrom1sCandles(long startMs, long endMs) {
        String sql = """
            SELECT
                symbol,
                market_type,
                COALESCE(
                    SUBSTRING_INDEX(MIN(CASE WHEN trade_count > 0 THEN CONCAT(LPAD(candle_time_ms,20,'0'),'|',open_price) END),'|',-1),
                    SUBSTRING_INDEX(MIN(CONCAT(LPAD(candle_time_ms,20,'0'),'|',open_price)),'|',-1)
                )                                                                                 AS open_price,
                COALESCE(MAX(CASE WHEN trade_count > 0 THEN high_price END), MAX(high_price))     AS high_price,
                COALESCE(MIN(CASE WHEN trade_count > 0 THEN low_price END),  MIN(low_price))      AS low_price,
                COALESCE(
                    SUBSTRING_INDEX(MAX(CASE WHEN trade_count > 0 THEN CONCAT(LPAD(candle_time_ms,20,'0'),'|',close_price) END),'|',-1),
                    SUBSTRING_INDEX(MAX(CONCAT(LPAD(candle_time_ms,20,'0'),'|',close_price)),'|',-1)
                )                                                                                 AS close_price,
                SUM(buy_volume)                                                                   AS buy_volume,
                SUM(sell_volume)                                                                  AS sell_volume,
                SUM(total_volume)                                                                 AS total_volume,
                SUM(buy_quantity)                                                                 AS buy_quantity,
                SUM(sell_quantity)                                                                AS sell_quantity,
                SUM(buy_trade_count)                                                              AS buy_trade_count,
                SUM(sell_trade_count)                                                             AS sell_trade_count,
                SUM(trade_count)                                                                  AS trade_count,
                MIN(min_agg_trade_id)                                                             AS min_agg_trade_id,
                MAX(max_agg_trade_id)                                                             AS max_agg_trade_id,
                MIN(min_first_trade_id)                                                           AS min_first_trade_id,
                MAX(max_last_trade_id)                                                            AS max_last_trade_id
            FROM agg_trade_1s
            WHERE candle_time_ms >= ? AND candle_time_ms < ?
            GROUP BY symbol, market_type
            """;
        return batchJdbcTemplate.queryForList(sql, startMs, endMs);
    }

    // ─── 5m: agg_trade_1m 집계 ────────────────────────────────────────────

    private List<Map<String, Object>> aggregateFrom1mCandles(long startMs, long endMs) {
        String sql = """
            SELECT
                symbol,
                market_type,
                FLOOR(candle_time_ms / 300000) * 300000  AS candle_time_ms,
                MIN(low_price)                           AS low_price,
                MAX(high_price)                          AS high_price,
                SUM(buy_volume)                          AS buy_volume,
                SUM(sell_volume)                         AS sell_volume,
                SUM(total_volume)                        AS total_volume,
                SUM(buy_quantity)                        AS buy_quantity,
                SUM(sell_quantity)                       AS sell_quantity,
                SUM(buy_trade_count)                     AS buy_trade_count,
                SUM(sell_trade_count)                    AS sell_trade_count,
                SUM(trade_count)                         AS trade_count,
                MIN(min_agg_trade_id)                    AS min_agg_trade_id,
                MAX(max_agg_trade_id)                    AS max_agg_trade_id,
                MIN(min_first_trade_id)                  AS min_first_trade_id,
                MAX(max_last_trade_id)                   AS max_last_trade_id
            FROM agg_trade_1m
            WHERE candle_time_ms >= ? AND candle_time_ms < ?
            GROUP BY symbol, market_type, FLOOR(candle_time_ms / 300000) * 300000
            """;
        return batchJdbcTemplate.queryForList(sql, startMs, endMs);
    }

    // ─── fillCandle ───────────────────────────────────────────────────────

    private void fill1mCandle(AggTrade1m c, Map<String, Object> row, long startMs) {
        c.setSymbol((String) row.get("symbol"));
        c.setMarketType((String) row.get("market_type"));
        c.setCandleTimeMs(startMs);
        c.setOpenPrice(toBd(row.get("open_price")));
        c.setHighPrice(toBd(row.get("high_price")));
        c.setLowPrice(toBd(row.get("low_price")));
        c.setClosePrice(toBd(row.get("close_price")));
        c.setBuyVolume(toBd(row.get("buy_volume")));
        c.setSellVolume(toBd(row.get("sell_volume")));
        c.setTotalVolume(toBd(row.get("total_volume")));
        c.setBuyQuantity(toBd(row.get("buy_quantity")));
        c.setSellQuantity(toBd(row.get("sell_quantity")));
        c.setDelta(c.getBuyQuantity().subtract(c.getSellQuantity()));
        c.setBuyTradeCount(toLong(row.get("buy_trade_count")));
        c.setSellTradeCount(toLong(row.get("sell_trade_count")));
        c.setTradeCount(toLong(row.get("trade_count")));
        c.setMinAggTradeId(toLong(row.get("min_agg_trade_id")));
        c.setMaxAggTradeId(toLong(row.get("max_agg_trade_id")));
        c.setMinFirstTradeId(toLong(row.get("min_first_trade_id")));
        c.setMaxLastTradeId(toLong(row.get("max_last_trade_id")));

        BigDecimal totalQty = c.getBuyQuantity().add(c.getSellQuantity());
        c.setVwap(totalQty.compareTo(BigDecimal.ZERO) == 0
            ? BigDecimal.ZERO
            : c.getTotalVolume().divide(totalQty, 8, RoundingMode.HALF_UP));
    }

    private void fill5mCandle(AggTrade5m c, Map<String, Object> row) {
        long startMs = toLong(row.get("candle_time_ms"));
        long endMs   = startMs + 300_000L;

        c.setSymbol((String) row.get("symbol"));
        c.setMarketType((String) row.get("market_type"));
        c.setCandleTimeMs(startMs);
        c.setOpenPrice(get1mFirstPrice((String) row.get("symbol"), (String) row.get("market_type"), startMs, endMs));
        c.setClosePrice(get1mLastPrice((String) row.get("symbol"), (String) row.get("market_type"), startMs, endMs));
        c.setHighPrice(toBd(row.get("high_price")));
        c.setLowPrice(toBd(row.get("low_price")));
        c.setBuyVolume(toBd(row.get("buy_volume")));
        c.setSellVolume(toBd(row.get("sell_volume")));
        c.setTotalVolume(toBd(row.get("total_volume")));
        c.setBuyQuantity(toBd(row.get("buy_quantity")));
        c.setSellQuantity(toBd(row.get("sell_quantity")));
        c.setDelta(c.getBuyQuantity().subtract(c.getSellQuantity()));
        c.setBuyTradeCount(toLong(row.get("buy_trade_count")));
        c.setSellTradeCount(toLong(row.get("sell_trade_count")));
        c.setTradeCount(toLong(row.get("trade_count")));
        c.setMinAggTradeId(toLong(row.get("min_agg_trade_id")));
        c.setMaxAggTradeId(toLong(row.get("max_agg_trade_id")));
        c.setMinFirstTradeId(toLong(row.get("min_first_trade_id")));
        c.setMaxLastTradeId(toLong(row.get("max_last_trade_id")));

        BigDecimal totalQty = c.getBuyQuantity().add(c.getSellQuantity());
        c.setVwap(totalQty.compareTo(BigDecimal.ZERO) == 0
            ? BigDecimal.ZERO
            : c.getTotalVolume().divide(totalQty, 8, RoundingMode.HALF_UP));
    }

    // ─── open/close 가격 조회 (5m용) ─────────────────────────────────────

    private BigDecimal get1mFirstPrice(String symbol, String marketType, long startMs, long endMs) {
        String sql = """
            SELECT open_price FROM agg_trade_1m
            WHERE symbol = ? AND market_type = ? AND candle_time_ms >= ? AND candle_time_ms < ?
            ORDER BY candle_time_ms ASC
            LIMIT 1
            """;
        List<BigDecimal> result = batchJdbcTemplate.queryForList(sql, BigDecimal.class, symbol, marketType, startMs, endMs);
        return result.isEmpty() ? BigDecimal.ZERO : result.get(0);
    }

    private BigDecimal get1mLastPrice(String symbol, String marketType, long startMs, long endMs) {
        String sql = """
            SELECT close_price FROM agg_trade_1m
            WHERE symbol = ? AND market_type = ? AND candle_time_ms >= ? AND candle_time_ms < ?
            ORDER BY candle_time_ms DESC
            LIMIT 1
            """;
        List<BigDecimal> result = batchJdbcTemplate.queryForList(sql, BigDecimal.class, symbol, marketType, startMs, endMs);
        return result.isEmpty() ? BigDecimal.ZERO : result.get(0);
    }

    // ─── 시각 계산 ────────────────────────────────────────────────────────

    /** 현재 분의 시작 ms (초·나노 버림) */
    private long currentMinuteStartMs() {
        long now = System.currentTimeMillis();
        return (now / 60_000L) * 60_000L;
    }

    /** 현재 5분 구간의 시작 ms */
    private long current5mStartMs() {
        long now = System.currentTimeMillis();
        return (now / 300_000L) * 300_000L;
    }

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
