package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.BinanceKlineTempCandle;
import com.chs.springboot.domain.binance.repository.AggTrade1mRepository;
import com.chs.springboot.domain.binance.repository.AggTrade5mRepository;
import com.chs.springboot.domain.binance.repository.BinanceKlineTempCandleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BinanceKlineSignalCandleSourceTest {

    private static final String SYMBOL = "BTCUSDT";
    private static final long MINUTE_MS = 60_000L;

    @Test
    void combinesSpotAndFuturesDeltaAndUsesFuturesPrice() {
        long now = System.currentTimeMillis();
        long time = (now / MINUTE_MS - 10) * MINUTE_MS;
        BinanceKlineTempCandle future = temp("FUTURES", time, 100, 10, 7, 70);
        BinanceKlineTempCandle spot = temp("SPOT", time, 90, 4, 1, 10);
        BinanceKlineSignalCandleSource source = source(0, List.of(spot), List.of(future));

        List<SignalCandleSource.SignalCandle> candles = source.find(
                SYMBOL, SignalCandleSource.Interval.ONE_MINUTE, time, now,
                SignalCandleSource.QueryMode.COMPLETED);

        assertThat(candles).hasSize(1);
        SignalCandleSource.SignalCandle candle = candles.get(0);
        assertThat(candle.openPrice()).isEqualByComparingTo("100");
        assertThat(candle.quoteVolume()).isEqualByComparingTo("70");
        assertThat(candle.baseVolume()).isEqualByComparingTo("10");
        assertThat(candle.delta()).isEqualByComparingTo("2");
    }

    @Test
    void skipsMinuteWithoutFuturesTempRow() {
        long now = System.currentTimeMillis();
        long time = (now / MINUTE_MS - 10) * MINUTE_MS;
        BinanceKlineTempCandle spot = temp("SPOT", time, 100, 10, 7, 70);
        BinanceKlineSignalCandleSource source = source(0, List.of(spot), List.of());

        List<SignalCandleSource.SignalCandle> candles = source.find(
                SYMBOL, SignalCandleSource.Interval.ONE_MINUTE, time, now,
                SignalCandleSource.QueryMode.COMPLETED);

        assertThat(candles).isEmpty();
    }

    @Test
    void sumsTempEnergyAcrossSpotAndFutures() {
        long now = System.currentTimeMillis();
        long time = (now / MINUTE_MS - 10) * MINUTE_MS;
        BinanceKlineTempCandle future = temp("FUTURES", time, 100, 10, 7, 50);
        BinanceKlineTempCandle spot = temp("SPOT", time, 90, 4, 1, 6);
        future.setQuoteVolume(BigDecimal.valueOf(70));
        future.setTakerBuyQuoteVolume(BigDecimal.valueOf(50));
        BinanceKlineSignalCandleSource source = source(0, List.of(spot), List.of(future));

        SignalCandleSource.Energy energy = source.sumEnergy(
                SYMBOL, SignalCandleSource.Interval.ONE_MINUTE, time, now,
                SignalCandleSource.QueryMode.COMPLETED);

        assertThat(energy.longEnergy()).isEqualByComparingTo("56");
        assertThat(energy.shortEnergy()).isEqualByComparingTo("20");
    }

    @Test
    void requiresFiveCompleteMinutesButAllowsPartialInProgressBucket() {
        long now = System.currentTimeMillis();
        long bucket = (now / 300_000L - 2) * 300_000L;
        List<BinanceKlineTempCandle> futures = java.util.stream.LongStream.range(0, 5)
                .mapToObj(i -> temp("FUTURES", bucket + i * MINUTE_MS, 100 + (int) i, 10, 7, 70))
                .toList();
        BinanceKlineSignalCandleSource source = source(0, List.of(), futures);

        List<SignalCandleSource.SignalCandle> complete = source.find(
                SYMBOL, SignalCandleSource.Interval.FIVE_MINUTES, bucket, now,
                SignalCandleSource.QueryMode.COMPLETED);
        assertThat(complete).hasSize(1);
        assertThat(complete.get(0).baseVolume()).isEqualByComparingTo("50");
        assertThat(complete.get(0).delta()).isEqualByComparingTo("20");

        BinanceKlineSignalCandleSource incompleteSource = source(0, List.of(), futures.subList(0, 4));
        assertThat(incompleteSource.find(SYMBOL, SignalCandleSource.Interval.FIVE_MINUTES, bucket, now,
                SignalCandleSource.QueryMode.COMPLETED)).isEmpty();

        BinanceKlineSignalCandleSource partialSource = source(0, List.of(), futures.subList(0, 1));
        assertThat(partialSource.find(SYMBOL, SignalCandleSource.Interval.FIVE_MINUTES, bucket, now,
                SignalCandleSource.QueryMode.IN_PROGRESS)).hasSize(1);
    }

    @Test
    void aggregatesThreeCompleteFiveMinuteBucketsIntoFifteenMinutes() {
        long now = System.currentTimeMillis();
        long bucket = (now / 900_000L - 2) * 900_000L;
        List<BinanceKlineTempCandle> futures = java.util.stream.LongStream.range(0, 15)
                .mapToObj(i -> temp("FUTURES", bucket + i * MINUTE_MS, 100 + (int) i, 2, 1, 5))
                .toList();
        List<BinanceKlineTempCandle> spot = java.util.stream.LongStream.range(0, 15)
                .mapToObj(i -> temp("SPOT", bucket + i * MINUTE_MS, 90 + (int) i, 4, 1, 5))
                .toList();
        BinanceKlineSignalCandleSource source = source(0, spot, futures);

        List<SignalCandleSource.SignalCandle> candles = source.find(
                SYMBOL, SignalCandleSource.Interval.FIFTEEN_MINUTES, bucket, now,
                SignalCandleSource.QueryMode.COMPLETED);

        assertThat(candles).hasSize(1);
        assertThat(candles.get(0).baseVolume()).isEqualByComparingTo("30");
        assertThat(candles.get(0).delta()).isEqualByComparingTo("-30");
    }

    @Test
    void prefersTempAtFixedHybridCutoverBoundary() {
        long legacyTime = 1_700_000_040_000L;
        long cutover = legacyTime + MINUTE_MS;
        BinanceKlineTempCandle temp = temp("FUTURES", cutover, 200, 10, 7, 70);
        BinanceKlineSignalCandleSource source = source(cutover,
                List.of(), List.of(temp));
        when(sourceAgg1m(source).findByTimeRangeWithCombinedDelta(SYMBOL, legacyTime, cutover))
                .thenReturn(List.of(
                        legacyRow(legacyTime, "100", "10", "4"),
                        legacyRow(cutover, "150", "10", "4")));

        List<SignalCandleSource.SignalCandle> candles = source.find(
                SYMBOL, SignalCandleSource.Interval.ONE_MINUTE, legacyTime, cutover + MINUTE_MS,
                SignalCandleSource.QueryMode.COMPLETED);

        assertThat(candles).extracting(SignalCandleSource.SignalCandle::timeMs)
                .containsExactly(legacyTime, cutover);
        assertThat(candles.get(1).closePrice()).isEqualByComparingTo("201");
    }

    @Test
    void usesFixedFiveAndFifteenMinuteFloorForCutover() {
        long fiveBoundary = 1_700_000_100_000L;
        long cutover = fiveBoundary + 4 * MINUTE_MS + 123L;
        List<BinanceKlineTempCandle> futures = java.util.stream.LongStream.range(0, 5)
                .mapToObj(i -> temp("FUTURES", fiveBoundary + i * MINUTE_MS, 200, 2, 1, 5))
                .toList();
        BinanceKlineSignalCandleSource fiveSource = source(cutover, List.of(), futures);
        when(sourceAgg5m(fiveSource).findByTimeRangeWithCombinedDelta(
                SYMBOL, fiveBoundary - 300_000L, fiveBoundary)).thenReturn(List.of());

        assertThat(fiveSource.find(SYMBOL, SignalCandleSource.Interval.FIVE_MINUTES,
                fiveBoundary - 300_000L, fiveBoundary + 300_000L, SignalCandleSource.QueryMode.COMPLETED))
                .extracting(SignalCandleSource.SignalCandle::timeMs)
                .containsExactly(fiveBoundary);

        long fifteenBoundary = (1_700_000_000_000L / 900_000L) * 900_000L;
        long fifteenCutover = fifteenBoundary + 8 * MINUTE_MS + 123L;
        List<BinanceKlineTempCandle> fifteenFutures = java.util.stream.LongStream.range(0, 15)
                .mapToObj(i -> temp("FUTURES", fifteenBoundary + i * MINUTE_MS, 220, 2, 1, 5))
                .toList();
        BinanceKlineSignalCandleSource fifteenSource = source(fifteenCutover, List.of(), fifteenFutures);
        assertThat(fifteenSource.find(SYMBOL, SignalCandleSource.Interval.FIFTEEN_MINUTES,
                fifteenBoundary, fifteenBoundary + 900_000L, SignalCandleSource.QueryMode.COMPLETED))
                .extracting(SignalCandleSource.SignalCandle::timeMs)
                .containsExactly(fifteenBoundary);
    }

    @Test
    void excludesNonAlignedRangeEdgesWithoutOffByOne() {
        long now = System.currentTimeMillis();
        long firstMinute = (now / MINUTE_MS - 10) * MINUTE_MS;
        List<BinanceKlineTempCandle> futures = List.of(
                temp("FUTURES", firstMinute, 100, 2, 1, 5),
                temp("FUTURES", firstMinute + MINUTE_MS, 110, 2, 1, 5));
        BinanceKlineSignalCandleSource source = source(0, List.of(), futures);

        List<SignalCandleSource.SignalCandle> candles = source.find(
                SYMBOL, SignalCandleSource.Interval.ONE_MINUTE, firstMinute + 1,
                firstMinute + 2 * MINUTE_MS - 1, SignalCandleSource.QueryMode.IN_PROGRESS);

        assertThat(candles).extracting(SignalCandleSource.SignalCandle::timeMs)
                .containsExactly(firstMinute + MINUTE_MS);
    }

    private BinanceKlineSignalCandleSource source(
            long cutover,
            List<BinanceKlineTempCandle> spot,
            List<BinanceKlineTempCandle> futures) {
        AggTrade1mRepository agg1m = mock(AggTrade1mRepository.class);
        AggTrade5mRepository agg5m = mock(AggTrade5mRepository.class);
        BinanceKlineTempCandleRepository temp = mock(BinanceKlineTempCandleRepository.class);
        when(temp.findBySymbolAndMarketTypeAndCandleTimeMsGreaterThanEqualAndCandleTimeMsLessThanOrderByCandleTimeMsAsc(
                eq(SYMBOL), eq("SPOT"), anyLong(), anyLong())).thenReturn(spot);
        when(temp.findBySymbolAndMarketTypeAndCandleTimeMsGreaterThanEqualAndCandleTimeMsLessThanOrderByCandleTimeMsAsc(
                eq(SYMBOL), eq("FUTURES"), anyLong(), anyLong())).thenReturn(futures);
        BinanceKlineSignalCandleSource source = new BinanceKlineSignalCandleSource(
                agg1m, agg5m, temp);
        ReflectionTestUtils.setField(source, "legacyCutoverMs", cutover);
        return source;
    }

    private AggTrade1mRepository sourceAgg1m(BinanceKlineSignalCandleSource source) {
        return (AggTrade1mRepository) ReflectionTestUtils.getField(source, "agg1mRepository");
    }

    private AggTrade5mRepository sourceAgg5m(BinanceKlineSignalCandleSource source) {
        return (AggTrade5mRepository) ReflectionTestUtils.getField(source, "agg5mRepository");
    }

    private Map<String, Object> legacyRow(long time, String price, String quote, String delta) {
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("candle_time_ms", time);
        row.put("open_price", price);
        row.put("high_price", price);
        row.put("low_price", price);
        row.put("close_price", price);
        row.put("total_volume", quote);
        row.put("base_volume", "1");
        row.put("delta", delta);
        return row;
    }

    private BinanceKlineTempCandle temp(String market, long time, int price, int volume, int takerBase, int takerQuote) {
        BinanceKlineTempCandle candle = new BinanceKlineTempCandle();
        candle.setSymbol(SYMBOL);
        candle.setMarketType(market);
        candle.setCandleTimeMs(time);
        candle.setCloseTimeMs(time + MINUTE_MS - 1);
        candle.setOpenPrice(BigDecimal.valueOf(price));
        candle.setHighPrice(BigDecimal.valueOf(price + 2L));
        candle.setLowPrice(BigDecimal.valueOf(price - 1L));
        candle.setClosePrice(BigDecimal.valueOf(price + 1L));
        candle.setVolume(BigDecimal.valueOf(volume));
        candle.setQuoteVolume(BigDecimal.valueOf(takerQuote));
        candle.setTradeCount(1L);
        candle.setTakerBuyBaseVolume(BigDecimal.valueOf(takerBase));
        candle.setTakerBuyQuoteVolume(BigDecimal.valueOf(takerQuote));
        return candle;
    }
}
