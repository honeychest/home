// [AGENT] 역할: 수집 데이터 누락 구간 탐지 서비스 | 연관파일: DataGapAdminController.java
// 지원 타입: RAW_AGG_TRADE, AGG_1M, AGG_5M, KLINE_5M, OI
// 기존 SQL 갭 쿼리는 최대 200개를 missing_count/gap_minutes 내림차순으로 반환하며 KLINE_5M은 범위 전체를 반환
package com.chs.springboot.domain.binance.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class DataGapAdminService {

    private static final int GAP_RESULT_LIMIT = 200;

    private final JdbcTemplate batchJdbcTemplate;
    private final BinanceKlineGapService klineGapService;

    public DataGapAdminService(JdbcTemplate batchJdbcTemplate, BinanceKlineGapService klineGapService) {
        this.batchJdbcTemplate = batchJdbcTemplate;
        this.klineGapService = klineGapService;
    }

    /** days: null이면 전체, 숫자면 최근 N일 */
    public List<Map<String, Object>> checkGap(String type, Integer days) {
        return checkGap(type, days, null, null);
    }

    public List<Map<String, Object>> checkGap(
            String type, Integer days, Long fromMs, Long toMsExclusive) {
        return switch (type.toUpperCase()) {
            case "RAW_AGG_TRADE" -> rawAggTradeGap(days);
            case "AGG_1M"        -> candleGap("agg_trade_1m", 60_000L);
            case "AGG_5M"        -> candleGap("agg_trade_5m", 300_000L);
            case "KLINE_5M"      -> klineGapService.findGaps(days, fromMs, toMsExclusive);
            case "OI"            -> oiGap();
            default -> throw new IllegalArgumentException("Unknown type: " + type);
        };
    }

    /** raw_agg_trade: agg_trade_id 연속성 갭 탐지. days=null이면 전체, 숫자면 최근 N일 */
    private List<Map<String, Object>> rawAggTradeGap(Integer days) {
        String whereClause = (days != null)
                ? "WHERE traded_at >= NOW() - INTERVAL " + days + " DAY"
                : "";
        String sql = """
            SELECT
                symbol,
                market_type,
                agg_trade_id + 1           AS gap_start_id,
                next_id - 1                AS gap_end_id,
                next_id - agg_trade_id - 1 AS missing_count
            FROM (
                SELECT
                    symbol,
                    market_type,
                    agg_trade_id,
                    LEAD(agg_trade_id) OVER (
                        PARTITION BY symbol, market_type
                        ORDER BY agg_trade_id
                    ) AS next_id
                FROM raw_agg_trade
                """ + whereClause + """
            ) t
            WHERE next_id - agg_trade_id > 1
            ORDER BY missing_count DESC
            LIMIT %d
            """.formatted(GAP_RESULT_LIMIT);
        return batchJdbcTemplate.queryForList(sql);
    }

    /** agg_trade_1m / agg_trade_5m: candle_time_ms 간격 갭 탐지 */
    private List<Map<String, Object>> candleGap(String table, long intervalMs) {
        String sql = String.format("""
            SELECT
                symbol,
                market_type,
                FROM_UNIXTIME((candle_time_ms + %d) / 1000)     AS gap_start,
                FROM_UNIXTIME((next_candle_ms - %d) / 1000)     AS gap_end,
                (next_candle_ms - candle_time_ms) / %d - 1      AS missing_candles,
                (candle_time_ms + %d)                            AS gap_start_ms,
                next_candle_ms                                   AS gap_end_ms
            FROM (
                SELECT
                    symbol,
                    market_type,
                    candle_time_ms,
                    LEAD(candle_time_ms) OVER (
                        PARTITION BY symbol, market_type
                        ORDER BY candle_time_ms
                    ) AS next_candle_ms
                FROM %s
            ) t
            WHERE next_candle_ms - candle_time_ms > %d
            ORDER BY missing_candles DESC
            LIMIT %d
            """, intervalMs, intervalMs, intervalMs, intervalMs, table, intervalMs, GAP_RESULT_LIMIT);
        return batchJdbcTemplate.queryForList(sql);
    }

    /** open_interest: 2분 이상 공백 탐지 (symbol 파티션) */
    private List<Map<String, Object>> oiGap() {
        String sql = """
            SELECT
                symbol,
                FROM_UNIXTIME(prev_time_ms / 1000)          AS gap_start,
                FROM_UNIXTIME(collected_at_ms / 1000)       AS gap_end,
                ROUND((collected_at_ms - prev_time_ms) / 60000.0, 2) AS gap_minutes,
                prev_time_ms                                AS gap_start_ms,
                collected_at_ms                             AS gap_end_ms
            FROM (
                SELECT
                    symbol,
                    collected_at_ms,
                    LAG(collected_at_ms) OVER (
                        PARTITION BY symbol
                        ORDER BY collected_at_ms
                    ) AS prev_time_ms
                FROM open_interest
            ) t
            WHERE collected_at_ms - prev_time_ms > 600000
            ORDER BY gap_minutes DESC
            LIMIT %d
            """.formatted(GAP_RESULT_LIMIT);
        return batchJdbcTemplate.queryForList(sql);
    }
}
