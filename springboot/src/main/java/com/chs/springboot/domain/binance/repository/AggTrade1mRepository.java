// [AGENT] T4-VOLUME-FIX: findTopNWithCombinedDelta() total_volume 추가 — 1m 차트 부피 데이터 표시 수정
// [AGENT] signal futures 캔들 시간범위 조회용 combined delta 메서드 추가
// [AGENT] 역할: AggTrade1m JPA Repository | 연관파일: AggTrade1m.java, AggTradeRollupService.java, SignalDataService.java, MainCandleChart.jsx | 주요메서드: sumEnergyBySymbolAndTimeRange, findTopNWithCombinedDelta(volume포함), insertIgnoreDuplicate, findByDateRange, findLastNBefore
package com.chs.springboot.domain.binance.repository;

import com.chs.springboot.domain.binance.model.AggTrade1m;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface AggTrade1mRepository extends JpaRepository<AggTrade1m, Long> {

    @Query("SELECT MAX(a.candleTimeMs) FROM AggTrade1m a WHERE a.symbol = :symbol AND a.marketType = :marketType")
    Optional<Long> findMaxCandleTimeMsBySymbolAndMarketType(
        @Param("symbol") String symbol,
        @Param("marketType") String marketType);

    @Query(value = """
        SELECT
            COALESCE(SUM(buy_volume),  0) AS long_energy,
            COALESCE(SUM(sell_volume), 0) AS short_energy
        FROM agg_trade_1m
        WHERE symbol = :symbol
          AND candle_time_ms >= :fromMs
          AND candle_time_ms < :toMs
        """, nativeQuery = true)
    Map<String, Object> sumEnergyBySymbolAndTimeRange(
        @Param("symbol") String symbol,
        @Param("fromMs")  long fromMs,
        @Param("toMs")    long toMs);

    // getCandles 용 — 최신 N봉 조회 (내림차순, 호출측에서 reverse 필요)
    @Query(value = """
        SELECT * FROM agg_trade_1m
        WHERE symbol = :symbol
          AND market_type = :marketType
        ORDER BY candle_time_ms DESC
        LIMIT :limitCount
        """, nativeQuery = true)
    List<AggTrade1m> findTopNBySymbolAndMarketType(
        @Param("symbol")     String symbol,
        @Param("marketType") String marketType,
        @Param("limitCount") int limitCount);

    // getCandles 용 — FUTURES 가격 + S+F delta 합산 + volume (내림차순, 호출측에서 reverse 필요)
    @Query(value = """
        SELECT f.candle_time_ms,
               f.open_price, f.high_price, f.low_price, f.close_price,
               f.total_volume,
               f.buy_quantity + f.sell_quantity AS base_volume,
               COALESCE(SUM(a.delta), 0) AS delta
        FROM agg_trade_1m f
        JOIN agg_trade_1m a ON a.symbol = f.symbol AND a.candle_time_ms = f.candle_time_ms
        WHERE f.symbol = :symbol
          AND f.market_type = 'FUTURES'
        GROUP BY f.candle_time_ms, f.open_price, f.high_price, f.low_price, f.close_price,
                 f.total_volume, f.buy_quantity + f.sell_quantity
        ORDER BY f.candle_time_ms DESC
        LIMIT :limitCount
        """, nativeQuery = true)
    List<Map<String, Object>> findTopNWithCombinedDelta(
        @Param("symbol")     String symbol,
        @Param("limitCount") int limitCount);

    @Query(value = """
        SELECT f.candle_time_ms,
               f.open_price, f.high_price, f.low_price, f.close_price,
               f.total_volume,
               f.buy_quantity + f.sell_quantity AS base_volume,
               COALESCE(SUM(a.delta), 0) AS delta
        FROM agg_trade_1m f
        JOIN agg_trade_1m a ON a.symbol = f.symbol AND a.candle_time_ms = f.candle_time_ms
        WHERE f.symbol = :symbol
          AND f.market_type = 'FUTURES'
          AND f.candle_time_ms >= :fromMs
          AND f.candle_time_ms < :toMs
        GROUP BY f.candle_time_ms, f.open_price, f.high_price, f.low_price, f.close_price,
                 f.total_volume, f.buy_quantity + f.sell_quantity
        ORDER BY f.candle_time_ms ASC
        """, nativeQuery = true)
    List<Map<String, Object>> findByTimeRangeWithCombinedDelta(
        @Param("symbol") String symbol,
        @Param("fromMs") long fromMs,
        @Param("toMs") long toMs);

    // delta API 용 — 시간 범위 내 FUTURES delta + FUTURES total_volume (오름차순)
    @Query(value = """
        SELECT f.candle_time_ms AS timeMs,
               (f.buy_quantity + f.sell_quantity) AS volume,
               COALESCE(SUM(a.delta), 0) AS delta
        FROM agg_trade_1m f
        JOIN agg_trade_1m a ON a.symbol = f.symbol AND a.candle_time_ms = f.candle_time_ms
                            AND a.market_type = 'FUTURES'
        WHERE f.symbol = :symbol
          AND f.market_type = 'FUTURES'
          AND f.candle_time_ms >= :startMs
          AND f.candle_time_ms < :endMs
        GROUP BY f.candle_time_ms, f.total_volume
        ORDER BY f.candle_time_ms ASC
        """, nativeQuery = true)
    List<Map<String, Object>> findDeltaByTimeRange(
        @Param("symbol")  String symbol,
        @Param("startMs") long startMs,
        @Param("endMs")   long endMs);

    // getCandlesByDate 용 — 날짜 범위 1m 봉 조회 (ASC, market_type=FUTURES 고정)
    @Query(value = """
        SELECT f.candle_time_ms,
               f.open_price, f.high_price, f.low_price, f.close_price,
               f.total_volume,
               COALESCE(SUM(a.delta), 0) AS delta
        FROM agg_trade_1m f
        JOIN agg_trade_1m a ON a.symbol = f.symbol AND a.candle_time_ms = f.candle_time_ms
        WHERE f.symbol = :symbol AND f.market_type = 'FUTURES'
          AND f.candle_time_ms >= :fromMs AND f.candle_time_ms < :toMs
        GROUP BY f.candle_time_ms, f.open_price, f.high_price, f.low_price, f.close_price, f.total_volume
        ORDER BY f.candle_time_ms ASC
        """, nativeQuery = true)
    List<Map<String, Object>> findByDateRange(
        @Param("symbol") String symbol,
        @Param("fromMs")  long fromMs,
        @Param("toMs")    long toMs);

    // getCandlesByDate 용 — day 시작 전 N봉 (DESC 반환 → 서비스에서 reverse)
    @Query(value = """
        SELECT f.candle_time_ms,
               f.open_price, f.high_price, f.low_price, f.close_price,
               f.total_volume,
               COALESCE(SUM(a.delta), 0) AS delta
        FROM agg_trade_1m f
        JOIN agg_trade_1m a ON a.symbol = f.symbol AND a.candle_time_ms = f.candle_time_ms
        WHERE f.symbol = :symbol AND f.market_type = 'FUTURES'
          AND f.candle_time_ms < :beforeMs
        GROUP BY f.candle_time_ms, f.open_price, f.high_price, f.low_price, f.close_price, f.total_volume
        ORDER BY f.candle_time_ms DESC
        LIMIT :n
        """, nativeQuery = true)
    List<Map<String, Object>> findLastNBefore(
        @Param("symbol")   String symbol,
        @Param("beforeMs") long beforeMs,
        @Param("n")        int n);

    @Transactional
    @Modifying
    @Query(value = """
        INSERT INTO agg_trade_1m
            (symbol, market_type, candle_time_ms,
             open_price, high_price, low_price, close_price, vwap,
             buy_volume, sell_volume, total_volume,
             buy_quantity, sell_quantity, delta,
             buy_trade_count, sell_trade_count, trade_count,
             min_agg_trade_id, max_agg_trade_id,
             min_first_trade_id, max_last_trade_id)
        VALUES
            (:#{#c.symbol}, :#{#c.marketType}, :#{#c.candleTimeMs},
             :#{#c.openPrice}, :#{#c.highPrice}, :#{#c.lowPrice}, :#{#c.closePrice}, :#{#c.vwap},
             :#{#c.buyVolume}, :#{#c.sellVolume}, :#{#c.totalVolume},
             :#{#c.buyQuantity}, :#{#c.sellQuantity}, :#{#c.delta},
             :#{#c.buyTradeCount}, :#{#c.sellTradeCount}, :#{#c.tradeCount},
             :#{#c.minAggTradeId}, :#{#c.maxAggTradeId},
             :#{#c.minFirstTradeId}, :#{#c.maxLastTradeId})
        ON DUPLICATE KEY UPDATE id = id
        """, nativeQuery = true)
    void insertIgnoreDuplicate(@Param("c") AggTrade1m candle);
}
