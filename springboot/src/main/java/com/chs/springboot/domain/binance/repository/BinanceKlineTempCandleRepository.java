package com.chs.springboot.domain.binance.repository;

import com.chs.springboot.domain.binance.model.BinanceKlineTempCandle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BinanceKlineTempCandleRepository extends JpaRepository<BinanceKlineTempCandle, Long> {

    @Query("""
            SELECT MAX(c.candleTimeMs)
            FROM BinanceKlineTempCandle c
            WHERE c.symbol = :symbol AND c.marketType = :marketType
            """)
    Optional<Long> findMaxCandleTimeMsBySymbolAndMarketType(
            @Param("symbol") String symbol,
            @Param("marketType") String marketType);

    List<BinanceKlineTempCandle> findBySymbolAndMarketTypeAndCandleTimeMsGreaterThanEqualAndCandleTimeMsLessThanOrderByCandleTimeMsAsc(
            String symbol,
            String marketType,
            long fromMs,
            long toMs);
}
