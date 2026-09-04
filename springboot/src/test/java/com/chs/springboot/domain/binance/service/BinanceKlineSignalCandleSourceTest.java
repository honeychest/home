package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.BinanceKline5m;
import com.chs.springboot.domain.binance.model.BinanceKlineTempCandle;
import com.chs.springboot.domain.binance.repository.AggTrade1mRepository;
import com.chs.springboot.domain.binance.repository.AggTrade5mRepository;
import com.chs.springboot.domain.binance.repository.BinanceKline5mRepository;
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
    private static final long FIVE_MINUTE_MS = 300_000L;

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
    void completedFiveMinuteReadsFromCanonicalAndCombinesSpotDelta() {
        long now = System.currentTimeMillis();
        long bucket = (now / FIVE_MINUTE_MS - 2) * FIVE_MINUTE_MS;
        BinanceKline5m future = canonical("FUTURES", bucket, 100, 10, 7, 70);
        BinanceKline5m spot = canonical("SPOT", bucket, 90, 4, 1, 10);
        BinanceKlineSignalCandleSource source = source(0, List.of(), List.of(), List.of(spot), List.of(future));

        List<SignalCandleSource.SignalCandle> candles = source.find(
                SYMBOL, SignalCandleSource.Interval.FIVE_MINUTES, bucket, now,
                SignalCandleSource.QueryMode.COMPLETED);

        assertThat(candles).hasSize(1);
        SignalCandleSource.SignalCandle candle = candles.get(0);
        assertThat(candle.openPrice()).isEqualByComparingTo("100");
        assertThat(candle.baseVolume()).isEqualByComparingTo("10");
        assertThat(candle.delta()).isEqualByComparingTo("2");
    }

    @Test
    void completedFiveMinuteKeepsFuturesCandleWhenSpotRowIsMissing() {
        long now = System.currentTimeMillis();
        long bucket = (now / FIVE_MINUTE_MS - 2) * FIVE_MINUTE_MS;
        BinanceKline5m future = canonical("FUTURES", bucket, 100, 10, 7, 70);
        BinanceKlineSignalCandleSource source = source(0, List.of(), List.of(), List.of(), List.of(future));

        List<SignalCandleSource.SignalCandle> candles = source.find(
                SYMBOL, SignalCandleSource.Interval.FIVE_MINUTES, bucket, now,
                SignalCandleSource.QueryMode.COMPLETED);

        assertThat(candles).hasSize(1);
        assertThat(candles.get(0).delta()).isEqualByComparingTo("4");
    }

    @Test
    void completedFiveMinuteHasNoCandleWhenCanonicalBucketIsMissing() {
        long now = System.currentTimeMillis();
        long bucket = (now / FIVE_MINUTE_MS - 2) * FIVE_MINUTE_MS;
        BinanceKlineSignalCandleSource source = source(0, List.of(), List.of(), List.of(), List.of());

        List<SignalCandleSource.SignalCandle> candles = source.find(
                SYMBOL, SignalCandleSource.Interval.FIVE_MINUTES, bucket, now,
                SignalCandleSource.QueryMode.COMPLETED);

        assertThat(candles).isEmpty();
    }

    @Test
    void inProgressFiveMinuteStillUsesPartialTempAggregationNotCanonical() {
        long now = System.currentTimeMillis();
        long bucket = (now / 300_000L - 2) * 300_000L;
        List<BinanceKlineTempCandle> futures = List.of(temp("FUTURES", bucket, 100, 10, 7, 70));
        // canonical에는 아무것도 없어도(완료봉만 저장) IN_PROGRESS는 temp 부분집계로 값을 낸다.
        BinanceKlineSignalCandleSource source = source(0, List.of(), futures, List.of(), List.of());

        List<SignalCandleSource.SignalCandle> candles = source.find(
                SYMBOL, SignalCandleSource.Interval.FIVE_MINUTES, bucket, now,
                SignalCandleSource.QueryMode.IN_PROGRESS);

        assertThat(candles).hasSize(1);
    }

    @Test
    void aggregatesThreeCompleteCanonicalFiveMinuteBucketsIntoFifteenMinutes() {
        long now = System.currentTimeMillis();
        long bucket = (now / 900_000L - 2) * 900_000L;
        List<BinanceKline5m> futures = List.of(
                canonical("FUTURES", bucket, 100, 2, 1, 5),
                canonical("FUTURES", bucket + FIVE_MINUTE_MS, 101, 2, 1, 5),
                canonical("FUTURES", bucket + 2 * FIVE_MINUTE_MS, 102, 2, 1, 5));
        List<BinanceKline5m> spot = List.of(
                canonical("SPOT", bucket, 90, 4, 1, 5),
                canonical("SPOT", bucket + FIVE_MINUTE_MS, 91, 4, 1, 5),
                canonical("SPOT", bucket + 2 * FIVE_MINUTE_MS, 92, 4, 1, 5));
        BinanceKlineSignalCandleSource source = source(0, List.of(), List.of(), spot, futures);

        List<SignalCandleSource.SignalCandle> candles = source.find(
                SYMBOL, SignalCandleSource.Interval.FIFTEEN_MINUTES, bucket, now,
                SignalCandleSource.QueryMode.COMPLETED);

        assertThat(candles).hasSize(1);
        assertThat(candles.get(0).baseVolume()).isEqualByComparingTo("6");
    }

    @Test
    void fifteenMinuteBucketIsIncompleteWhenOneCanonicalFiveMinuteRowIsMissing() {
        long now = System.currentTimeMillis();
        long bucket = (now / 900_000L - 2) * 900_000L;
        List<BinanceKline5m> futures = List.of(
                canonical("FUTURES", bucket, 100, 2, 1, 5),
                canonical("FUTURES", bucket + 2 * FIVE_MINUTE_MS, 102, 2, 1, 5));
        BinanceKlineSignalCandleSource source = source(0, List.of(), List.of(), List.of(), futures);

        List<SignalCandleSource.SignalCandle> candles = source.find(
                SYMBOL, SignalCandleSource.Interval.FIFTEEN_MINUTES, bucket, now,
                SignalCandleSource.QueryMode.COMPLETED);

        assertThat(candles).isEmpty();
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
        List<BinanceKline5m> futures = List.of(canonical("FUTURES", fiveBoundary, 200, 2, 1, 5));
        BinanceKlineSignalCandleSource fiveSource = source(cutover, List.of(), List.of(), List.of(), futures);
        when(sourceAgg5m(fiveSource).findByTimeRangeWithCombinedDelta(
                SYMBOL, fiveBoundary - 300_000L, fiveBoundary)).thenReturn(List.of());

        assertThat(fiveSource.find(SYMBOL, SignalCandleSource.Interval.FIVE_MINUTES,
                fiveBoundary - 300_000L, fiveBoundary + 300_000L, SignalCandleSource.QueryMode.COMPLETED))
                .extracting(SignalCandleSource.SignalCandle::timeMs)
                .containsExactly(fiveBoundary);

        long fifteenBoundary = (1_700_000_000_000L / 900_000L) * 900_000L;
        long fifteenCutover = fifteenBoundary + 8 * MINUTE_MS + 123L;
        List<BinanceKline5m> fifteenFutures = List.of(
                canonical("FUTURES", fifteenBoundary, 220, 2, 1, 5),
                canonical("FUTURES", fifteenBoundary + FIVE_MINUTE_MS, 221, 2, 1, 5),
                canonical("FUTURES", fifteenBoundary + 2 * FIVE_MINUTE_MS, 222, 2, 1, 5));
        BinanceKlineSignalCandleSource fifteenSource =
                source(fifteenCutover, List.of(), List.of(), List.of(), fifteenFutures);
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

    @Test
    void findBeforeReadsCompletedCandlesFromCanonical() {
        long now = System.currentTimeMillis();
        long bucket = (now / FIVE_MINUTE_MS - 5) * FIVE_MINUTE_MS;
        List<BinanceKline5m> futures = List.of(
                canonical("FUTURES", bucket, 100, 2, 1, 5),
                canonical("FUTURES", bucket + FIVE_MINUTE_MS, 101, 2, 1, 5));
        BinanceKlineSignalCandleSource source = source(0, List.of(), List.of(), List.of(), futures);

        List<SignalCandleSource.SignalCandle> candles = source.findBefore(
                SYMBOL, SignalCandleSource.Interval.FIVE_MINUTES, bucket + 2 * FIVE_MINUTE_MS, 5,
                SignalCandleSource.QueryMode.COMPLETED);

        assertThat(candles).extracting(SignalCandleSource.SignalCandle::timeMs)
                .containsExactly(bucket, bucket + FIVE_MINUTE_MS);
    }

    private BinanceKlineSignalCandleSource source(
            long cutover,
            List<BinanceKlineTempCandle> spot,
            List<BinanceKlineTempCandle> futures) {
        return source(cutover, spot, futures, List.of(), List.of());
    }

    private BinanceKlineSignalCandleSource source(
            long cutover,
            List<BinanceKlineTempCandle> spot,
            List<BinanceKlineTempCandle> futures,
            List<BinanceKline5m> canonicalSpot,
            List<BinanceKline5m> canonicalFutures) {
        AggTrade1mRepository agg1m = mock(AggTrade1mRepository.class);
        AggTrade5mRepository agg5m = mock(AggTrade5mRepository.class);
        BinanceKlineTempCandleRepository temp = mock(BinanceKlineTempCandleRepository.class);
        BinanceKline5mRepository canonical = mock(BinanceKline5mRepository.class);
        when(temp.findBySymbolAndMarketTypeAndCandleTimeMsGreaterThanEqualAndCandleTimeMsLessThanOrderByCandleTimeMsAsc(
                eq(SYMBOL), eq("SPOT"), anyLong(), anyLong())).thenReturn(spot);
        when(temp.findBySymbolAndMarketTypeAndCandleTimeMsGreaterThanEqualAndCandleTimeMsLessThanOrderByCandleTimeMsAsc(
                eq(SYMBOL), eq("FUTURES"), anyLong(), anyLong())).thenReturn(futures);
        when(canonical.findBySymbolAndMarketTypeAndCandleTimeMsGreaterThanEqualAndCandleTimeMsLessThanOrderByCandleTimeMsAsc(
                eq(SYMBOL), eq("SPOT"), anyLong(), anyLong())).thenReturn(canonicalSpot);
        when(canonical.findBySymbolAndMarketTypeAndCandleTimeMsGreaterThanEqualAndCandleTimeMsLessThanOrderByCandleTimeMsAsc(
                eq(SYMBOL), eq("FUTURES"), anyLong(), anyLong())).thenReturn(canonicalFutures);
        BinanceKlineSignalCandleSource source = new BinanceKlineSignalCandleSource(
                agg1m, agg5m, temp, canonical);
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

    private BinanceKline5m canonical(String market, long time, int price, int volume, int takerBase, int takerQuote) {
        BinanceKline5m candle = new BinanceKline5m();
        candle.setSymbol(SYMBOL);
        candle.setMarketType(market);
        candle.setCandleTimeMs(time);
        candle.setCloseTimeMs(time + FIVE_MINUTE_MS - 1);
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
