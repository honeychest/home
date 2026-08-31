package com.chs.springboot.domain.binance.model;

import java.math.BigDecimal;

/**
 * Binance 1분봉을 로컬에서 5분 단위로 묶은 조회 결과.
 * 이 결과는 DB에 저장하지 않고 요청 시 계산한다.
 */
public record BinanceKlineFiveMinute(
        String symbol,
        String marketType,
        long candleTimeMs,
        long closeTimeMs,
        BigDecimal openPrice,
        BigDecimal highPrice,
        BigDecimal lowPrice,
        BigDecimal closePrice,
        BigDecimal volume,
        BigDecimal quoteVolume,
        long tradeCount,
        BigDecimal takerBuyBaseVolume,
        BigDecimal takerBuyQuoteVolume
) {

    public BigDecimal vwap() {
        if (volume.signum() == 0) {
            return closePrice;
        }
        return quoteVolume.divide(volume, 16, java.math.RoundingMode.HALF_UP);
    }
}
