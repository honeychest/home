package com.chs.springboot.domain.binance.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Binance kline API에서 받은 1분봉을 보관하는 검증용 임시 테이블 매핑.
 * 기존 agg_trade_1m 및 agg_trade_5m 테이블과는 읽기와 쓰기 모두 분리한다.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(
        name = "agg_trade_1m_temp",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_agg_trade_1m_temp",
                columnNames = {"symbol", "market_type", "candle_time_ms"}
        ),
        indexes = {
                @Index(name = "idx_agg_trade_1m_temp_candle_time", columnList = "candle_time_ms")
        }
)
public class BinanceKlineTempCandle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "market_type", nullable = false, length = 10)
    private String marketType;

    @Column(name = "candle_time_ms", nullable = false)
    private Long candleTimeMs;

    @Column(name = "close_time_ms", nullable = false)
    private Long closeTimeMs;

    @Column(name = "open_price", nullable = false, precision = 30, scale = 16)
    private BigDecimal openPrice;

    @Column(name = "high_price", nullable = false, precision = 30, scale = 16)
    private BigDecimal highPrice;

    @Column(name = "low_price", nullable = false, precision = 30, scale = 16)
    private BigDecimal lowPrice;

    @Column(name = "close_price", nullable = false, precision = 30, scale = 16)
    private BigDecimal closePrice;

    @Column(name = "volume", nullable = false, precision = 30, scale = 16)
    private BigDecimal volume;

    @Column(name = "quote_volume", nullable = false, precision = 30, scale = 16)
    private BigDecimal quoteVolume;

    @Column(name = "trade_count", nullable = false)
    private Long tradeCount;

    @Column(name = "taker_buy_base_volume", nullable = false, precision = 30, scale = 16)
    private BigDecimal takerBuyBaseVolume;

    @Column(name = "taker_buy_quote_volume", nullable = false, precision = 30, scale = 16)
    private BigDecimal takerBuyQuoteVolume;

    public static BinanceKlineTempCandle from(String symbol, String marketType, BinanceKline kline) {
        BinanceKlineTempCandle candle = new BinanceKlineTempCandle();
        candle.symbol = symbol;
        candle.marketType = marketType;
        candle.candleTimeMs = kline.openTimeMs();
        candle.closeTimeMs = kline.closeTimeMs();
        candle.openPrice = kline.openPrice();
        candle.highPrice = kline.highPrice();
        candle.lowPrice = kline.lowPrice();
        candle.closePrice = kline.closePrice();
        candle.volume = kline.volume();
        candle.quoteVolume = kline.quoteVolume();
        candle.tradeCount = kline.tradeCount();
        candle.takerBuyBaseVolume = kline.takerBuyBaseVolume();
        candle.takerBuyQuoteVolume = kline.takerBuyQuoteVolume();
        return candle;
    }
}
