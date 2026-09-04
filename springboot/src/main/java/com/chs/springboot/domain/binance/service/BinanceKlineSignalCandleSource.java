package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.BinanceKline5m;
import com.chs.springboot.domain.binance.model.BinanceKlineFiveMinute;
import com.chs.springboot.domain.binance.model.BinanceKlineTempCandle;
import com.chs.springboot.domain.binance.repository.AggTrade1mRepository;
import com.chs.springboot.domain.binance.repository.AggTrade5mRepository;
import com.chs.springboot.domain.binance.repository.BinanceKline5mRepository;
import com.chs.springboot.domain.binance.repository.BinanceKlineTempCandleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Phase 4 이후 시그널·차트의 공통 캔들 원천.
 * cutover 이전에는 기존 aggregate 테이블을 읽고, 이후에는 kline shadow를 읽어 시장을 합성한다.
 */
@Slf4j
@Service
public class BinanceKlineSignalCandleSource implements SignalCandleSource {

    private static final long MINUTE_MS = 60_000L;
    private static final long FIVE_MINUTE_MS = 300_000L;
    private static final long KST_OFFSET_SECONDS = 9 * 60 * 60L;

    private final AggTrade1mRepository agg1mRepository;
    private final AggTrade5mRepository agg5mRepository;
    private final BinanceKlineTempCandleRepository tempCandleRepository;
    private final BinanceKline5mRepository canonicalRepository;
    private final BinanceKlineFiveMinuteAggregator fiveMinuteAggregator = new BinanceKlineFiveMinuteAggregator();

    @Value("${binance.agg-trade.legacy-cutover-ms:0}")
    private long legacyCutoverMs;

    public BinanceKlineSignalCandleSource(
            AggTrade1mRepository agg1mRepository,
            AggTrade5mRepository agg5mRepository,
            BinanceKlineTempCandleRepository tempCandleRepository,
            BinanceKline5mRepository canonicalRepository) {
        this.agg1mRepository = agg1mRepository;
        this.agg5mRepository = agg5mRepository;
        this.tempCandleRepository = tempCandleRepository;
        this.canonicalRepository = canonicalRepository;
    }

    @Override
    public List<SignalCandle> find(
            String symbol,
            Interval interval,
            long fromMs,
            long toMsExclusive,
            QueryMode mode) {
        long effectiveTo = effectiveTo(toMsExclusive, interval, mode);
        if (effectiveTo <= fromMs) {
            return List.of();
        }

        long legacyEnd = legacyEnd(interval);
        List<SignalCandle> legacy = fromMs < legacyEnd
                ? findLegacy(symbol, interval, fromMs, Math.min(effectiveTo, legacyEnd), mode)
                : List.of();
        long tempFrom = Math.max(fromMs, legacyEnd);
        List<SignalCandle> temp = tempFrom < effectiveTo
                ? findTemp(symbol, interval, tempFrom, effectiveTo, mode)
                : List.of();

        return merge(legacy, temp);
    }

    @Override
    public List<SignalCandle> findBefore(
            String symbol,
            Interval interval,
            long beforeMs,
            int limit,
            QueryMode mode) {
        if (limit <= 0) {
            return List.of();
        }
        long effectiveBefore = effectiveTo(beforeMs, interval, mode);
        if (effectiveBefore <= 0) {
            return List.of();
        }

        long legacyEnd = legacyEnd(interval);
        long tempFrom = legacyEnd;
        List<SignalCandle> temp = tempFrom < effectiveBefore
                ? findTemp(symbol, interval, tempFrom, effectiveBefore, mode)
                : List.of();

        long legacyTo = Math.min(effectiveBefore, legacyEnd);
        long legacyFrom = Math.max(0, legacyTo - interval.durationMs() * Math.max(10L, limit * 3L));
        List<SignalCandle> legacy = legacyFrom < legacyTo
                ? findLegacy(symbol, interval, legacyFrom, legacyTo, mode)
                : List.of();

        List<SignalCandle> merged = merge(legacy, temp);
        if (merged.size() <= limit) {
            return merged;
        }
        return new ArrayList<>(merged.subList(merged.size() - limit, merged.size()));
    }

    @Override
    public List<SignalCandle> findByQuoteVolume(
            String symbol,
            Interval interval,
            BigDecimal minQuoteVolume,
            BigDecimal maxQuoteVolume,
            QueryMode mode) {
        return find(symbol, interval, 0, System.currentTimeMillis(), mode).stream()
                .filter(c -> c.quoteVolume().compareTo(minQuoteVolume) >= 0
                        && c.quoteVolume().compareTo(maxQuoteVolume) <= 0)
                .toList();
    }

    @Override
    public List<String> findCandleDates(String symbol) {
        Set<String> dates = new TreeSet<>(Comparator.reverseOrder());
        for (String date : agg5mRepository.findDistinctKstDates(symbol)) {
            if (date != null) {
                dates.add(date);
            }
        }

        long nowMs = System.currentTimeMillis();
        long tempFrom = legacyEnd(Interval.FIVE_MINUTES);
        for (BinanceKlineTempCandle candle : tempRows(symbol, "FUTURES", tempFrom, nowMs)) {
            dates.add(Instant.ofEpochMilli(candle.getCandleTimeMs())
                    .atZone(ZoneOffset.ofTotalSeconds((int) KST_OFFSET_SECONDS))
                    .toLocalDate().toString());
        }
        return new ArrayList<>(dates);
    }

    @Override
    public Energy sumEnergy(
            String symbol,
            Interval interval,
            long fromMs,
            long toMsExclusive,
            QueryMode mode) {
        long effectiveTo = effectiveTo(toMsExclusive, interval, mode);
        if (effectiveTo <= fromMs) {
            return new Energy(BigDecimal.ZERO, BigDecimal.ZERO);
        }

        BigDecimal longEnergy = BigDecimal.ZERO;
        BigDecimal shortEnergy = BigDecimal.ZERO;
        long legacyEnd = legacyEnd(interval);
        long legacyTo = Math.min(effectiveTo, legacyEnd);
        if (fromMs < legacyTo) {
            Map<String, Object> row = interval == Interval.ONE_MINUTE
                    ? agg1mRepository.sumEnergyBySymbolAndTimeRange(symbol, fromMs, legacyTo)
                    : agg5mRepository.sumEnergyBySymbolAndTimeRange(symbol, fromMs, legacyTo);
            longEnergy = decimal(row.get("long_energy"));
            shortEnergy = decimal(row.get("short_energy"));
        }

        long tempFrom = Math.max(fromMs, legacyEnd);
        if (tempFrom < effectiveTo) {
            for (String marketType : List.of("SPOT", "FUTURES")) {
                for (BinanceKlineTempCandle candle : tempRows(symbol, marketType, tempFrom, effectiveTo)) {
                    BigDecimal quote = decimal(candle.getQuoteVolume());
                    BigDecimal buyQuote = decimal(candle.getTakerBuyQuoteVolume());
                    longEnergy = longEnergy.add(buyQuote);
                    shortEnergy = shortEnergy.add(quote.subtract(buyQuote));
                }
            }
        }
        return new Energy(longEnergy, shortEnergy);
    }

    private List<SignalCandle> findLegacy(
            String symbol,
            Interval interval,
            long fromMs,
            long toMsExclusive,
            QueryMode mode) {
        if (toMsExclusive <= fromMs) {
            return List.of();
        }
        if (interval == Interval.ONE_MINUTE) {
            return mapLegacyRows(symbol, agg1mRepository.findByTimeRangeWithCombinedDelta(
                    symbol, fromMs, toMsExclusive));
        }
        if (interval == Interval.FIVE_MINUTES) {
            return mapLegacyRows(symbol, agg5mRepository.findByTimeRangeWithCombinedDelta(
                    symbol, fromMs, toMsExclusive));
        }

        long queryFrom = Math.floorDiv(fromMs, FIVE_MINUTE_MS) * FIVE_MINUTE_MS;
        List<SignalCandle> five = mapLegacyRows(symbol,
                agg5mRepository.findByTimeRangeWithCombinedDelta(symbol, queryFrom, toMsExclusive));
        return aggregate(five, interval, FIVE_MINUTE_MS, mode).stream()
                .filter(c -> c.timeMs() >= fromMs && c.timeMs() < toMsExclusive)
                .toList();
    }

    private List<SignalCandle> findTemp(
            String symbol,
            Interval interval,
            long fromMs,
            long toMsExclusive,
            QueryMode mode) {
        if (interval == Interval.ONE_MINUTE) {
            return combineTempOneMinute(symbol, fromMs, toMsExclusive);
        }
        long queryFrom = Math.floorDiv(fromMs, interval.durationMs()) * interval.durationMs();
        // COMPLETED 5분/15분은 canonical(binance_kline_5m)로 읽는다 — 완료봉만 저장하므로
        // IN_PROGRESS는 기존 1분-temp 기반 부분집계 경로를 그대로 쓴다(치명4 해결책, v3 6단계).
        List<SignalCandle> five = mode == QueryMode.COMPLETED
                ? findCanonicalFive(symbol, queryFrom, toMsExclusive)
                : findTempFive(symbol, queryFrom, toMsExclusive, mode);
        if (interval == Interval.FIVE_MINUTES) {
            return five.stream().filter(c -> c.timeMs() >= fromMs).toList();
        }
        return aggregate(five, interval, FIVE_MINUTE_MS, mode).stream()
                .filter(c -> c.timeMs() >= fromMs)
                .toList();
    }

    /**
     * canonical 5분 표에서 SPOT+FUTURES를 합성한다. temp 기반 {@link #findTempFive}와 계약이
     * 같다 — FUTURES 행이 있어야 캔들을 만들고(가격·OHLC·volume은 FUTURES 기준), SPOT 행이
     * 있으면 delta에만 더한다. canonical은 완료봉만 저장하므로 완결 조건 검사가 필요 없다.
     */
    private List<SignalCandle> findCanonicalFive(String symbol, long fromMs, long toMsExclusive) {
        if (toMsExclusive <= fromMs) {
            return List.of();
        }
        Map<Long, BinanceKline5m> spotByTime = byCanonicalTime(canonicalRows(symbol, "SPOT", fromMs, toMsExclusive));
        List<BinanceKline5m> futureRows = canonicalRows(symbol, "FUTURES", fromMs, toMsExclusive);

        List<SignalCandle> result = new ArrayList<>(futureRows.size());
        for (BinanceKline5m future : futureRows) {
            long timeMs = future.getCandleTimeMs();
            BinanceKline5m spot = spotByTime.get(timeMs);
            BigDecimal delta = canonicalDelta(future);
            if (spot != null) {
                delta = delta.add(canonicalDelta(spot));
            }
            result.add(new SignalCandle(
                    symbol,
                    timeMs,
                    future.getOpenPrice(),
                    future.getHighPrice(),
                    future.getLowPrice(),
                    future.getClosePrice(),
                    future.getQuoteVolume(),
                    future.getVolume(),
                    delta));
        }
        result.sort(Comparator.comparingLong(SignalCandle::timeMs));
        return result;
    }

    private List<BinanceKline5m> canonicalRows(String symbol, String marketType, long fromMs, long toMsExclusive) {
        return canonicalRepository
                .findBySymbolAndMarketTypeAndCandleTimeMsGreaterThanEqualAndCandleTimeMsLessThanOrderByCandleTimeMsAsc(
                        symbol, marketType, fromMs, toMsExclusive);
    }

    private Map<Long, BinanceKline5m> byCanonicalTime(List<BinanceKline5m> rows) {
        Map<Long, BinanceKline5m> result = new LinkedHashMap<>();
        for (BinanceKline5m row : rows) {
            result.put(row.getCandleTimeMs(), row);
        }
        return result;
    }

    private BigDecimal canonicalDelta(BinanceKline5m candle) {
        return candle.getTakerBuyBaseVolume().multiply(BigDecimal.valueOf(2)).subtract(candle.getVolume());
    }

    private List<SignalCandle> combineTempOneMinute(String symbol, long fromMs, long toMsExclusive) {
        long queryFrom = Math.floorDiv(fromMs, MINUTE_MS) * MINUTE_MS;
        Map<Long, BinanceKlineTempCandle> spot = byTime(tempRows(symbol, "SPOT", queryFrom, toMsExclusive));
        Map<Long, BinanceKlineTempCandle> futures = byTime(tempRows(symbol, "FUTURES", queryFrom, toMsExclusive));

        List<SignalCandle> result = new ArrayList<>(futures.size());
        for (BinanceKlineTempCandle future : futures.values()) {
            long timeMs = future.getCandleTimeMs();
            if (timeMs < fromMs || timeMs >= toMsExclusive) {
                continue;
            }
            BinanceKlineTempCandle spotCandle = spot.get(timeMs);
            BigDecimal delta = delta(future);
            if (spotCandle != null) {
                delta = delta.add(delta(spotCandle));
            }
            result.add(new SignalCandle(
                    symbol,
                    timeMs,
                    decimal(future.getOpenPrice()),
                    decimal(future.getHighPrice()),
                    decimal(future.getLowPrice()),
                    decimal(future.getClosePrice()),
                    decimal(future.getQuoteVolume()),
                    decimal(future.getVolume()),
                    delta));
        }
        result.sort(Comparator.comparingLong(SignalCandle::timeMs));
        return result;
    }

    private List<SignalCandle> findTempFive(
            String symbol,
            long fromMs,
            long toMsExclusive,
            QueryMode mode) {
        long queryFrom = Math.floorDiv(fromMs, MINUTE_MS) * MINUTE_MS;
        List<BinanceKlineTempCandle> spotRows = tempRows(symbol, "SPOT", queryFrom, toMsExclusive);
        List<BinanceKlineTempCandle> futureRows = tempRows(symbol, "FUTURES", queryFrom, toMsExclusive);
        List<BinanceKlineFiveMinute> spotFives = fiveMinuteAggregator.aggregate(spotRows);
        List<BinanceKlineFiveMinute> futureFives = fiveMinuteAggregator.aggregate(futureRows);
        Map<Long, BinanceKlineFiveMinute> spotByBucket = spotFives.stream()
                .collect(java.util.stream.Collectors.toMap(BinanceKlineFiveMinute::candleTimeMs, c -> c));
        Map<Long, BinanceKlineFiveMinute> futureByBucket = futureFives.stream()
                .collect(java.util.stream.Collectors.toMap(BinanceKlineFiveMinute::candleTimeMs, c -> c));

        Set<Long> futureTimes = new HashSet<>(futureRows.stream()
                .map(BinanceKlineTempCandle::getCandleTimeMs)
                .toList());
        List<SignalCandle> result = new ArrayList<>(futureByBucket.size());
        for (Map.Entry<Long, BinanceKlineFiveMinute> entry : futureByBucket.entrySet()) {
            long bucket = entry.getKey();
            if (mode == QueryMode.COMPLETED && !hasCompleteBucket(futureTimes, bucket, MINUTE_MS, 5)) {
                continue;
            }
            BinanceKlineFiveMinute future = entry.getValue();
            BinanceKlineFiveMinute spot = spotByBucket.get(bucket);
            BigDecimal delta = klineDelta(future);
            if (spot != null) {
                delta = delta.add(klineDelta(spot));
            }
            result.add(new SignalCandle(
                    symbol,
                    bucket,
                    future.openPrice(),
                    future.highPrice(),
                    future.lowPrice(),
                    future.closePrice(),
                    future.quoteVolume(),
                    future.volume(),
                    delta));
        }
        result.sort(Comparator.comparingLong(SignalCandle::timeMs));
        return result.stream().filter(c -> c.timeMs() >= fromMs && c.timeMs() < toMsExclusive).toList();
    }

    private List<SignalCandle> aggregate(
            List<SignalCandle> input,
            Interval target,
            long inputDurationMs,
            QueryMode mode) {
        Map<Long, List<SignalCandle>> buckets = new TreeMap<>();
        for (SignalCandle candle : input) {
            long bucket = Math.floorDiv(candle.timeMs(), target.durationMs()) * target.durationMs();
            buckets.computeIfAbsent(bucket, ignored -> new ArrayList<>()).add(candle);
        }

        int expected = Math.toIntExact(target.durationMs() / inputDurationMs);
        List<SignalCandle> result = new ArrayList<>(buckets.size());
        for (Map.Entry<Long, List<SignalCandle>> entry : buckets.entrySet()) {
            List<SignalCandle> candles = entry.getValue();
            candles.sort(Comparator.comparingLong(SignalCandle::timeMs));
            Set<Long> times = candles.stream().map(SignalCandle::timeMs).collect(java.util.stream.Collectors.toSet());
            if (mode == QueryMode.COMPLETED && !hasCompleteBucket(times, entry.getKey(), inputDurationMs, expected)) {
                continue;
            }
            SignalCandle first = candles.get(0);
            SignalCandle last = candles.get(candles.size() - 1);
            BigDecimal high = first.highPrice();
            BigDecimal low = first.lowPrice();
            BigDecimal quoteVolume = BigDecimal.ZERO;
            BigDecimal baseVolume = BigDecimal.ZERO;
            BigDecimal delta = BigDecimal.ZERO;
            for (SignalCandle candle : candles) {
                high = high.max(candle.highPrice());
                low = low.min(candle.lowPrice());
                quoteVolume = quoteVolume.add(candle.quoteVolume());
                baseVolume = baseVolume.add(candle.baseVolume());
                delta = delta.add(candle.delta());
            }
            result.add(new SignalCandle(
                    first.symbol(), entry.getKey(), first.openPrice(), high, low, last.closePrice(),
                    quoteVolume, baseVolume, delta));
        }
        return result;
    }

    private boolean hasCompleteBucket(Set<Long> times, long bucket, long inputDurationMs, int expected) {
        if (times.size() < expected) {
            return false;
        }
        for (int i = 0; i < expected; i++) {
            if (!times.contains(bucket + i * inputDurationMs)) {
                return false;
            }
        }
        return true;
    }

    private List<SignalCandle> mapLegacyRows(String symbol, List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.stream().map(row -> new SignalCandle(
                symbol,
                number(row.get("candle_time_ms")).longValue(),
                decimal(row.get("open_price")),
                decimal(row.get("high_price")),
                decimal(row.get("low_price")),
                decimal(row.get("close_price")),
                decimal(row.get("total_volume")),
                decimal(row.get("base_volume")),
                decimal(row.get("delta")))).sorted(Comparator.comparingLong(SignalCandle::timeMs)).toList();
    }

    private List<SignalCandle> merge(List<SignalCandle> legacy, List<SignalCandle> temp) {
        Map<Long, SignalCandle> merged = new TreeMap<>();
        for (SignalCandle candle : legacy) {
            merged.put(candle.timeMs(), candle);
        }
        for (SignalCandle candle : temp) {
            merged.put(candle.timeMs(), candle);
        }
        return new ArrayList<>(merged.values());
    }

    private List<BinanceKlineTempCandle> tempRows(String symbol, String marketType, long fromMs, long toMsExclusive) {
        if (toMsExclusive <= fromMs) {
            return List.of();
        }
        return tempCandleRepository
                .findBySymbolAndMarketTypeAndCandleTimeMsGreaterThanEqualAndCandleTimeMsLessThanOrderByCandleTimeMsAsc(
                        symbol, marketType, fromMs, toMsExclusive);
    }

    private Map<Long, BinanceKlineTempCandle> byTime(List<BinanceKlineTempCandle> rows) {
        Map<Long, BinanceKlineTempCandle> result = new LinkedHashMap<>();
        for (BinanceKlineTempCandle row : rows) {
            result.put(row.getCandleTimeMs(), row);
        }
        return result;
    }

    private long legacyEnd(Interval interval) {
        return Math.floorDiv(legacyCutoverMs, interval.durationMs()) * interval.durationMs();
    }

    private long effectiveTo(long toMsExclusive, Interval interval, QueryMode mode) {
        if (mode == QueryMode.IN_PROGRESS) {
            return toMsExclusive;
        }
        long nowBoundary = Math.floorDiv(System.currentTimeMillis(), interval.durationMs()) * interval.durationMs();
        return Math.min(toMsExclusive, nowBoundary);
    }

    private BigDecimal delta(BinanceKlineTempCandle candle) {
        return decimal(candle.getTakerBuyBaseVolume()).multiply(BigDecimal.valueOf(2))
                .subtract(decimal(candle.getVolume()));
    }

    private BigDecimal klineDelta(BinanceKlineFiveMinute candle) {
        return candle.takerBuyBaseVolume().multiply(BigDecimal.valueOf(2)).subtract(candle.volume());
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        return new BigDecimal(value.toString());
    }

    private static BigDecimal number(Object value) {
        return decimal(value);
    }
}
