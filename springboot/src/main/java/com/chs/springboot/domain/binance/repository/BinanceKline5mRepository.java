package com.chs.springboot.domain.binance.repository;

import com.chs.springboot.domain.binance.model.BinanceKline5m;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BinanceKline5mRepository extends JpaRepository<BinanceKline5m, Long> {

    @Query("""
            SELECT MAX(c.candleTimeMs)
            FROM BinanceKline5m c
            WHERE c.symbol = :symbol AND c.marketType = :marketType
            """)
    Optional<Long> findMaxCandleTimeMsBySymbolAndMarketType(
            @Param("symbol") String symbol,
            @Param("marketType") String marketType);

    List<BinanceKline5m> findBySymbolAndMarketTypeAndCandleTimeMsGreaterThanEqualAndCandleTimeMsLessThanOrderByCandleTimeMsAsc(
            String symbol,
            String marketType,
            long fromMs,
            long toMs);
}
