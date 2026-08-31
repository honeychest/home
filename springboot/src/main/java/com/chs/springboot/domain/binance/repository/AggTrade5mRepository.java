// [AGENT] T4-STEALTH: AggTrade5m JPA Repository | 연관파일: AggTrade5m.java, AggTradeRollupService.java, SignalDataService.java, PatternMatchService.java | 주요메서드: sumEnergyBySymbolAndTimeRange, findBySymbolAndVolumeBetween, findBySymbolAndMarketTypeAndCandleTimeMsAfter, insertIgnoreDuplicate, sumDivergenceBySymbolAndTimeRange, findBySymbolAndTimeRange, findDayCandlesBySymbol, findTopNWithCombinedDelta(volume추가), findByTimeRangeWithCombinedDelta, findByDateRange, findLastNBefore, findDistinctKstDates, findSimilarCandle, findDeltaByTimeRange
package com.chs.springboot.domain.binance.repository;

import com.chs.springboot.domain.binance.model.AggTrade5m;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface AggTrade5mRepository extends JpaRepository<AggTrade5m, Long> {

    @Query("SELECT MAX(a.candleTimeMs) FROM AggTrade5m a WHERE a.symbol = :symbol AND a.marketType = :marketType")
    Optional<Long> findMaxCandleTimeMsBySymbolAndMarketType(
        @Param("symbol") String symbol,
        @Param("marketType") String marketType);

    @Query(value = """
        SELECT
            COALESCE(SUM(buy_volume),  0) AS long_energy,
            COALESCE(SUM(sell_volume), 0) AS short_energy
        FROM agg_trade_5m
        WHERE symbol = :symbol
          AND candle_time_ms >= :fromMs
          AND candle_time_ms < :toMs
        """, nativeQuery = true)
    Map<String, Object> sumEnergyBySymbolAndTimeRange(
        @Param("symbol") String symbol,
        @Param("fromMs")  long fromMs,
        @Param("toMs")    long toMs);

    // findPatterns 용 — (buy_volume + sell_volume) 범위 조회
    @Query(value = """
        SELECT * FROM agg_trade_5m
        WHERE symbol = :symbol
          AND (buy_volume + sell_volume) BETWEEN :minVol AND :maxVol
        ORDER BY candle_time_ms DESC
        """, nativeQuery = true)
    List<AggTrade5m> findBySymbolAndVolumeBetween(
        @Param("symbol") String symbol,
        @Param("minVol") BigDecimal minVol,
        @Param("maxVol") BigDecimal maxVol);

    // calcMovingAverage 용 — 특정 시각 이후 봉 조회
    List<AggTrade5m> findBySymbolAndMarketTypeAndCandleTimeMsAfterOrderByCandleTimeMsDesc(
        String symbol, String marketType, long candleTimeMsAfter);

    // getDivergence 용 — 기간 내 첫 open, 마지막 close, delta 합산
    @Query(value = """
        SELECT
            (SELECT open_price FROM agg_trade_5m
             WHERE symbol = :symbol AND candle_time_ms >= :fromMs AND candle_time_ms < :toMs
             ORDER BY candle_time_ms ASC LIMIT 1) AS first_open,
            (SELECT close_price FROM agg_trade_5m
             WHERE symbol = :symbol AND candle_time_ms >= :fromMs AND candle_time_ms < :toMs
             ORDER BY candle_time_ms DESC LIMIT 1) AS last_close,
            COALESCE(SUM(delta), 0) AS total_delta
        FROM agg_trade_5m
        WHERE symbol = :symbol
          AND candle_time_ms >= :fromMs
          AND candle_time_ms < :toMs
        """, nativeQuery = true)
    Map<String, Object> sumDivergenceBySymbolAndTimeRange(
        @Param("symbol") String symbol,
        @Param("fromMs")  long fromMs,
        @Param("toMs")    long toMs);

    // PatternMatchService 용 — 심볼 전체 히스토리 (최근 2년) 봉 조회
    @Query(value = """
        SELECT * FROM agg_trade_5m
        WHERE symbol = :symbol
          AND candle_time_ms >= :fromMs
        ORDER BY candle_time_ms ASC
        """, nativeQuery = true)
    List<AggTrade5m> findBySymbolAndTimeRange(
        @Param("symbol") String symbol,
        @Param("fromMs")  long fromMs);

    // PatternMatchService 용 — 특정 일봉 하루치(00:00~23:59) 5분봉 조회
    @Query(value = """
        SELECT * FROM agg_trade_5m
        WHERE symbol = :symbol
          AND candle_time_ms >= :dayStartMs
          AND candle_time_ms < :dayEndMs
        ORDER BY candle_time_ms ASC
        """, nativeQuery = true)
    List<AggTrade5m> findDayCandlesBySymbol(
        @Param("symbol")     String symbol,
        @Param("dayStartMs") long dayStartMs,
        @Param("dayEndMs")   long dayEndMs);

    // getCandles 용 — 최신 N봉 조회 (내림차순, 호출측에서 reverse 필요)
    @Query(value = """
        SELECT * FROM agg_trade_5m
        WHERE symbol = :symbol
          AND market_type = :marketType
        ORDER BY candle_time_ms DESC
        LIMIT :limitCount
        """, nativeQuery = true)
    List<AggTrade5m> findTopNBySymbolAndMarketType(
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
        FROM agg_trade_5m f
        JOIN agg_trade_5m a ON a.symbol = f.symbol AND a.candle_time_ms = f.candle_time_ms
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
        FROM agg_trade_5m f
        JOIN agg_trade_5m a ON a.symbol = f.symbol AND a.candle_time_ms = f.candle_time_ms
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

    // getCandlesByDate 용 — 날짜 범위 day 봉 조회 (ASC, market_type=FUTURES 고정)
    @Query(value = """
        SELECT f.candle_time_ms,
               f.open_price, f.high_price, f.low_price, f.close_price,
               f.total_volume,
               COALESCE(SUM(a.delta), 0) AS delta
        FROM agg_trade_5m f
        JOIN agg_trade_5m a ON a.symbol = f.symbol AND a.candle_time_ms = f.candle_time_ms
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
        FROM agg_trade_5m f
        JOIN agg_trade_5m a ON a.symbol = f.symbol AND a.candle_time_ms = f.candle_time_ms
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

    // getCandleDates 용 — KST 거래일 날짜 목록 (CONVERT_TZ로 UTC→KST 명시 변환)
    @Query(value = """
        SELECT DISTINCT DATE(CONVERT_TZ(FROM_UNIXTIME(candle_time_ms / 1000), '+00:00', '+09:00')) AS trade_date
        FROM agg_trade_5m
        WHERE symbol = :symbol AND market_type = 'FUTURES'
        ORDER BY trade_date DESC
        """, nativeQuery = true)
    List<String> findDistinctKstDates(@Param("symbol") String symbol);

    // getCandles 용 — 심볼·마켓타입·기간 내 봉 조회 (OHLC 히스토리)
    @Query(value = """
        SELECT * FROM agg_trade_5m
        WHERE symbol = :symbol
          AND market_type = :marketType
          AND candle_time_ms >= :fromMs
          AND candle_time_ms < :toMs
        ORDER BY candle_time_ms ASC
        """, nativeQuery = true)
    List<AggTrade5m> findCandlesBySymbolAndMarketTypeAndTimeRange(
        @Param("symbol")     String symbol,
        @Param("marketType") String marketType,
        @Param("fromMs")     long fromMs,
        @Param("toMs")       long toMs);

    // Signal 수동 탐색 — 전봉 대비 등락율 기준 유사 봉 조회 (LAG 서브쿼리)
    @Query(value = """
        SELECT candle_time_ms, open_price, high_price, low_price, close_price, total_volume
        FROM (
            SELECT candle_time_ms, open_price, high_price, low_price, close_price, total_volume,
                   LAG(close_price) OVER (ORDER BY candle_time_ms) AS prev_close
            FROM agg_trade_5m
            WHERE symbol = :symbol
              AND market_type = 'FUTURES'
              AND candle_time_ms >= :fromMs
              AND candle_time_ms < :toMs
        ) t
        WHERE prev_close IS NOT NULL
          AND (:useRateFilter = 0 OR (close_price - prev_close) / prev_close * 100
              BETWEEN (:priceChangeRate - :rateTolerance) AND (:priceChangeRate + :rateTolerance))
          AND (:useVolFilter = 0 OR total_volume BETWEEN :volMin AND :volMax)
        ORDER BY candle_time_ms DESC
        LIMIT 1
        """, nativeQuery = true)
    List<Object[]> findSimilarCandle(
        @Param("symbol")          String symbol,
        @Param("fromMs")          long fromMs,
        @Param("toMs")            long toMs,
        @Param("priceChangeRate") double priceChangeRate,
        @Param("rateTolerance")   double rateTolerance,
        @Param("volMin")          java.math.BigDecimal volMin,
        @Param("volMax")          java.math.BigDecimal volMax,
        @Param("useRateFilter")   int useRateFilter,
        @Param("useVolFilter")    int useVolFilter);

    // Analysis 수동 탐색 — 범위 내 조건 충족 전체 봉 조회 (LIMIT 없음, ASC)
    @Query(value = """
        SELECT candle_time_ms, open_price, high_price, low_price, close_price, total_volume
        FROM (
            SELECT candle_time_ms, open_price, high_price, low_price, close_price, total_volume,
                   LAG(close_price) OVER (ORDER BY candle_time_ms) AS prev_close
            FROM agg_trade_5m
            WHERE symbol = :symbol
              AND market_type = 'FUTURES'
              AND candle_time_ms >= :fromMs
              AND candle_time_ms < :toMs
        ) t
        WHERE prev_close IS NOT NULL
          AND (:useRateFilter = 0 OR (close_price - prev_close) / prev_close * 100
              BETWEEN (:priceChangeRate - :rateTolerance) AND (:priceChangeRate + :rateTolerance))
          AND (:useVolFilter = 0 OR total_volume BETWEEN :volMin AND :volMax)
        ORDER BY candle_time_ms ASC
        """, nativeQuery = true)
    List<Object[]> findAllSimilarCandles(
        @Param("symbol")          String symbol,
        @Param("fromMs")          long fromMs,
        @Param("toMs")            long toMs,
        @Param("priceChangeRate") double priceChangeRate,
        @Param("rateTolerance")   double rateTolerance,
        @Param("volMin")          java.math.BigDecimal volMin,
        @Param("volMax")          java.math.BigDecimal volMax,
        @Param("useRateFilter")   int useRateFilter,
        @Param("useVolFilter")    int useVolFilter);

    // Analysis 수동 탐색 — 5분봉 3개를 15분봉으로 집계한 뒤 범위 내 조건 충족 전체 봉 조회
    @Query(value = """
        WITH candles AS (
            SELECT
                FLOOR(candle_time_ms / 900000) * 900000 AS candle_time_ms,
                SUBSTRING_INDEX(MIN(CONCAT(LPAD(candle_time_ms,20,'0'),'|',open_price)),'|',-1)  AS open_price,
                MAX(high_price)                                                                   AS high_price,
                MIN(low_price)                                                                    AS low_price,
                SUBSTRING_INDEX(MAX(CONCAT(LPAD(candle_time_ms,20,'0'),'|',close_price)),'|',-1) AS close_price,
                COALESCE(SUM(total_volume), 0)                                                    AS total_volume
            FROM agg_trade_5m
            WHERE symbol = :symbol
              AND market_type = 'FUTURES'
              AND candle_time_ms >= :fromMs
              AND candle_time_ms < :toMs
            GROUP BY FLOOR(candle_time_ms / 900000) * 900000
        ),
        with_prev AS (
            SELECT candle_time_ms, open_price, high_price, low_price, close_price, total_volume,
                   LAG(close_price) OVER (ORDER BY candle_time_ms) AS prev_close
            FROM candles
        )
        SELECT candle_time_ms, open_price, high_price, low_price, close_price, total_volume
        FROM with_prev
        WHERE prev_close IS NOT NULL
          AND (:useRateFilter = 0 OR (close_price - prev_close) / prev_close * 100
              BETWEEN (:priceChangeRate - :rateTolerance) AND (:priceChangeRate + :rateTolerance))
          AND (:useVolFilter = 0 OR total_volume BETWEEN :volMin AND :volMax)
        ORDER BY candle_time_ms ASC
        """, nativeQuery = true)
    List<Object[]> findAllSimilarCandles15m(
        @Param("symbol")          String symbol,
        @Param("fromMs")          long fromMs,
        @Param("toMs")            long toMs,
        @Param("priceChangeRate") double priceChangeRate,
        @Param("rateTolerance")   double rateTolerance,
        @Param("volMin")          java.math.BigDecimal volMin,
        @Param("volMax")          java.math.BigDecimal volMax,
        @Param("useRateFilter")   int useRateFilter,
        @Param("useVolFilter")    int useVolFilter);

    @Transactional
    @Modifying
    @Query(value = """
        INSERT INTO agg_trade_5m
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
    void insertIgnoreDuplicate(@Param("c") AggTrade5m candle);

    // Analysis delta 조회 — 5분봉 기준 candle_time_ms + delta + total_volume (FUTURES only)
    @Query(value = """
        SELECT candle_time_ms AS timeMs, (buy_quantity + sell_quantity) AS volume, delta
        FROM agg_trade_5m
        WHERE symbol    = :symbol
          AND market_type = 'FUTURES'
          AND candle_time_ms >= :startMs
          AND candle_time_ms <  :endMs
        ORDER BY candle_time_ms ASC
        """, nativeQuery = true)
    List<Map<String, Object>> findDeltaByTimeRange(
        @Param("symbol")  String symbol,
        @Param("startMs") long startMs,
        @Param("endMs")   long endMs);

    // Analysis delta 조회 — 15분봉 기준, 5분봉 FUTURES 3개를 시간 버킷으로 집계
    @Query(value = """
        SELECT FLOOR(candle_time_ms / 900000) * 900000 AS timeMs,
               COALESCE(SUM(buy_quantity + sell_quantity), 0) AS volume,
               COALESCE(SUM(delta), 0) AS delta
        FROM agg_trade_5m
        WHERE symbol    = :symbol
          AND market_type = 'FUTURES'
          AND candle_time_ms >= :startMs
          AND candle_time_ms <  :endMs
        GROUP BY FLOOR(candle_time_ms / 900000) * 900000
        ORDER BY timeMs ASC
        """, nativeQuery = true)
    List<Map<String, Object>> findDelta15mByTimeRange(
        @Param("symbol")  String symbol,
        @Param("startMs") long startMs,
        @Param("endMs")   long endMs);
}
