package com.chs.springboot.domain.analysis.service;

import com.chs.springboot.domain.analysis.dto.AnalysisSearchRequest;
import com.chs.springboot.domain.binance.service.SignalCandleSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisSearchServiceTest {

    @Test
    void searchUsesNormalizedSymbolAndSharedCandleSource() {
        SignalCandleSource candleSource = mock(SignalCandleSource.class);
        when(candleSource.find(eq("BTCUSDT"), eq(SignalCandleSource.Interval.FIVE_MINUTES),
                eq(0L), eq(1_000_000L), eq(SignalCandleSource.QueryMode.COMPLETED)))
                .thenReturn(List.of(
                        candle(0L, 100, 10),
                        candle(300_000L, 101, 10)));

        List<Long> result = new AnalysisSearchService(candleSource)
                .search(request("btc", "5m", 1.0, 0.1, 10, 1));

        assertEquals(List.of(300_000L), result);
        verify(candleSource).find(eq("BTCUSDT"), eq(SignalCandleSource.Interval.FIVE_MINUTES),
                eq(0L), eq(1_000_000L), eq(SignalCandleSource.QueryMode.COMPLETED));
    }

    @Test
    void searchCarriesPreviousCandleAcrossReadChunks() {
        SignalCandleSource candleSource = mock(SignalCandleSource.class);
        long chunkEnd = 7L * 24 * 60 * 60 * 1000;
        when(candleSource.find(eq("BTCUSDT"), eq(SignalCandleSource.Interval.ONE_MINUTE),
                eq(0L), eq(chunkEnd), eq(SignalCandleSource.QueryMode.COMPLETED)))
                .thenReturn(List.of(candle(chunkEnd - 60_000L, 100, 10)));
        when(candleSource.find(eq("BTCUSDT"), eq(SignalCandleSource.Interval.ONE_MINUTE),
                eq(chunkEnd), eq(chunkEnd + 60_000L), eq(SignalCandleSource.QueryMode.COMPLETED)))
                .thenReturn(List.of(candle(chunkEnd, 101, 10)));

        List<Long> result = new AnalysisSearchService(candleSource)
                .search(request("BTCUSDT", "1m", 1.0, 0.1, 10, 1));

        assertEquals(List.of(chunkEnd), result);
    }

    private AnalysisSearchRequest request(
            String symbol,
            String timeframe,
            double priceChangeRate,
            double rateTolerance,
            double totalVolume,
            double volTolerance) {
        AnalysisSearchRequest request = new AnalysisSearchRequest();
        request.setSymbol(symbol);
        request.setTimeframe(timeframe);
        request.setFromMs(0L);
        request.setToMs(timeframe.equals("1m")
                ? 7L * 24 * 60 * 60 * 1000 + 60_000L
                : 1_000_000L);

        AnalysisSearchRequest.Conditions conditions = new AnalysisSearchRequest.Conditions();
        conditions.setPriceChangeRate(priceChangeRate);
        conditions.setRateTolerance(rateTolerance);
        conditions.setTotalVolume(BigDecimal.valueOf(totalVolume));
        conditions.setVolTolerance(volTolerance);
        request.setConditions(conditions);
        return request;
    }

    private static SignalCandleSource.SignalCandle candle(long timeMs, double close, double volume) {
        BigDecimal price = BigDecimal.valueOf(close);
        BigDecimal amount = BigDecimal.valueOf(volume);
        return new SignalCandleSource.SignalCandle(
                "BTCUSDT", timeMs, price, price, price, price,
                amount, amount, BigDecimal.ZERO);
    }
}
