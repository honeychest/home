package com.chs.springboot.domain.binance.model;

import java.math.BigDecimal;

/**
 * Binance REST kline 응답 한 건의 원본 값.
 * openTimeMs는 1분봉 시작 시각이며, 나머지 거래량 값은 Binance가 제공한 값이다.
 */
public record BinanceKline(
        long openTimeMs,
        BigDecimal openPrice,
        BigDecimal highPrice,
        BigDecimal lowPrice,
        BigDecimal closePrice,
        BigDecimal volume,
        long closeTimeMs,
        BigDecimal quoteVolume,
        long tradeCount,
        BigDecimal takerBuyBaseVolume,
        BigDecimal takerBuyQuoteVolume
) {
}
