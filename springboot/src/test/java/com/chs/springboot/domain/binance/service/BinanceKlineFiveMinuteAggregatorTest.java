package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.BinanceKlineFiveMinute;
import com.chs.springboot.domain.binance.model.BinanceKlineTempCandle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BinanceKlineFiveMinuteAggregatorTest {

    @Test
    void groupsOneMinuteCandlesIntoLocalFiveMinuteOhlcv() {
        BinanceKlineTempCandle first = candle(0L, "10", "12", "9", "11", "2", "20", 3, "1", "10");
        BinanceKlineTempCandle second = candle(60_000L, "11", "13", "10", "12", "4", "48", 5, "2", "24");
        BinanceKlineTempCandle third = candle(240_000L, "12", "14", "11", "13", "3", "39", 7, "1", "13");

        List<BinanceKlineFiveMinute> result = new BinanceKlineFiveMinuteAggregator()
                .aggregate(List.of(third, first, second));

        BinanceKlineFiveMinute candle = result.get(0);
        assertEquals(0L, candle.candleTimeMs());
        assertEquals(new BigDecimal("10"), candle.openPrice());
        assertEquals(new BigDecimal("14"), candle.highPrice());
        assertEquals(new BigDecimal("9"), candle.lowPrice());
        assertEquals(new BigDecimal("13"), candle.closePrice());
        assertEquals(new BigDecimal("9"), candle.volume());
        assertEquals(new BigDecimal("107"), candle.quoteVolume());
        assertEquals(15L, candle.tradeCount());
        assertEquals(new BigDecimal("4"), candle.takerBuyBaseVolume());
        assertEquals(new BigDecimal("47"), candle.takerBuyQuoteVolume());
    }

    private BinanceKlineTempCandle candle(
            long timeMs,
            String open,
            String high,
            String low,
            String close,
            String volume,
            String quoteVolume,
            long tradeCount,
            String takerBuyBase,
            String takerBuyQuote) {
        BinanceKlineTempCandle candle = new BinanceKlineTempCandle();
        candle.setSymbol("BTCUSDT");
        candle.setMarketType("SPOT");
        candle.setCandleTimeMs(timeMs);
        candle.setCloseTimeMs(timeMs + 59_999L);
        candle.setOpenPrice(new BigDecimal(open));
        candle.setHighPrice(new BigDecimal(high));
        candle.setLowPrice(new BigDecimal(low));
        candle.setClosePrice(new BigDecimal(close));
        candle.setVolume(new BigDecimal(volume));
        candle.setQuoteVolume(new BigDecimal(quoteVolume));
        candle.setTradeCount(tradeCount);
        candle.setTakerBuyBaseVolume(new BigDecimal(takerBuyBase));
        candle.setTakerBuyQuoteVolume(new BigDecimal(takerBuyQuote));
        return candle;
    }
}
