package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.BinanceKlineFiveMinute;
import com.chs.springboot.domain.binance.model.BinanceKlineTempCandle;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 임시 1분봉 행을 5분 시각 버킷으로 묶는다.
 * 호출부가 필요한 기간만 조회하므로 전체 데이터를 메모리에 적재하지 않는다.
 */
public final class BinanceKlineFiveMinuteAggregator {

    private static final long FIVE_MINUTES_MS = 300_000L;

    public List<BinanceKlineFiveMinute> aggregate(List<BinanceKlineTempCandle> candles) {
        Map<Long, List<BinanceKlineTempCandle>> byBucket = new TreeMap<>();
        for (BinanceKlineTempCandle candle : candles) {
            long bucket = Math.floorDiv(candle.getCandleTimeMs(), FIVE_MINUTES_MS) * FIVE_MINUTES_MS;
            byBucket.computeIfAbsent(bucket, ignored -> new ArrayList<>()).add(candle);
        }

        List<BinanceKlineFiveMinute> result = new ArrayList<>(byBucket.size());
        for (Map.Entry<Long, List<BinanceKlineTempCandle>> entry : byBucket.entrySet()) {
            List<BinanceKlineTempCandle> bucketCandles = entry.getValue();
            bucketCandles.sort(Comparator.comparing(BinanceKlineTempCandle::getCandleTimeMs));
            BinanceKlineTempCandle first = bucketCandles.get(0);
            BinanceKlineTempCandle last = bucketCandles.get(bucketCandles.size() - 1);

            BigDecimal highPrice = first.getHighPrice();
            BigDecimal lowPrice = first.getLowPrice();
            BigDecimal volume = BigDecimal.ZERO;
            BigDecimal quoteVolume = BigDecimal.ZERO;
            BigDecimal takerBuyBaseVolume = BigDecimal.ZERO;
            BigDecimal takerBuyQuoteVolume = BigDecimal.ZERO;
            long tradeCount = 0L;
            for (BinanceKlineTempCandle candle : bucketCandles) {
                highPrice = highPrice.max(candle.getHighPrice());
                lowPrice = lowPrice.min(candle.getLowPrice());
                volume = volume.add(candle.getVolume());
                quoteVolume = quoteVolume.add(candle.getQuoteVolume());
                takerBuyBaseVolume = takerBuyBaseVolume.add(candle.getTakerBuyBaseVolume());
                takerBuyQuoteVolume = takerBuyQuoteVolume.add(candle.getTakerBuyQuoteVolume());
                tradeCount += candle.getTradeCount();
            }

            result.add(new BinanceKlineFiveMinute(
                    first.getSymbol(),
                    first.getMarketType(),
                    entry.getKey(),
                    last.getCloseTimeMs(),
                    first.getOpenPrice(),
                    highPrice,
                    lowPrice,
                    last.getClosePrice(),
                    volume,
                    quoteVolume,
                    tradeCount,
                    takerBuyBaseVolume,
                    takerBuyQuoteVolume
            ));
        }
        return result;
    }
}
