package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.AggTradeCollectStatus;
import com.chs.springboot.domain.binance.model.BinanceKline5m;
import com.chs.springboot.domain.binance.model.BinanceKlineInterval;
import com.chs.springboot.domain.binance.repository.AggTradeCollectStatusRepository;
import com.chs.springboot.domain.binance.repository.BinanceKline5mRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Binance 5분 kline과 binance_kline_5m의 candle_time 차이만 계산한다.
 * 주기적인 전체 repair pass는 두지 않고 관리자가 조회한 범위만 보장한다.
 */
@Service
public class BinanceKlineGapService {

    private static final long HOUR_MS = 3_600_000L;
    private static final long DAY_MS = 24L * HOUR_MS;
    private static final long MAX_RANGE_MS = 48L * HOUR_MS;
    private static final BinanceKlineInterval INTERVAL = BinanceKlineInterval.FIVE_MINUTES;
    private static final long INTERVAL_MS = INTERVAL.intervalMs();

    private final AggTradeCollectStatusRepository statusRepository;
    private final BinanceKline5mRepository canonicalRepository;
    private final BinanceKlineRangeFetcher rangeFetcher;
    private final Clock clock;

    @Autowired
    public BinanceKlineGapService(
            AggTradeCollectStatusRepository statusRepository,
            BinanceKline5mRepository canonicalRepository,
            BinanceKlineRestClient restClient) {
        this(statusRepository, canonicalRepository,
                new BinanceKlineRangeFetcher(restClient, INTERVAL), Clock.systemUTC());
    }

    BinanceKlineGapService(
            AggTradeCollectStatusRepository statusRepository,
            BinanceKline5mRepository canonicalRepository,
            BinanceKlineRangeFetcher rangeFetcher,
            Clock clock) {
        this.statusRepository = statusRepository;
        this.canonicalRepository = canonicalRepository;
        this.rangeFetcher = rangeFetcher;
        this.clock = clock;
    }

    public List<java.util.Map<String, Object>> findGaps(Integer days, Long fromMs, Long toMsExclusive) {
        long[] range = resolveRange(days, fromMs, toMsExclusive);
        List<java.util.Map<String, Object>> result = new ArrayList<>();
        for (AggTradeCollectStatus status : statusRepository.findByEnabledTrue()) {
            String symbol = status.getSymbol();
            String marketType = status.getMarketType();
            try {
                Set<Long> stored = new TreeSet<>(canonicalRepository
                        .findBySymbolAndMarketTypeAndCandleTimeMsGreaterThanEqualAndCandleTimeMsLessThanOrderByCandleTimeMsAsc(
                                symbol, marketType, range[0], range[1])
                        .stream()
                        .map(BinanceKline5m::getCandleTimeMs)
                        .toList());
                BinanceKlineRangeFetcher.RangeResult fetched = rangeFetcher.fetch(symbol, marketType, range[0], range[1]);
                if (fetched.firstPageEmpty()) {
                    result.add(errorRow(symbol, marketType,
                            "Binance kline 첫 응답이 비어 있어 갭 결과를 확정할 수 없습니다"));
                    continue;
                }
                TreeSet<Long> missing = new TreeSet<>(fetched.openTimes());
                missing.removeAll(stored);
                result.addAll(toGapRows(symbol, marketType, missing));
            } catch (Exception e) {
                result.add(errorRow(symbol, marketType,
                        "Binance kline 갭 조회 실패: " + safeMessage(e)));
            }
        }
        return result;
    }

    private long[] resolveRange(Integer days, Long fromMs, Long toMsExclusive) {
        boolean hasFrom = fromMs != null;
        boolean hasTo = toMsExclusive != null;
        if (hasFrom != hasTo) {
            throw new IllegalArgumentException("kline 갭 조회는 fromMs와 toMsExclusive를 함께 보내야 합니다");
        }
        if (hasFrom && days != null) {
            throw new IllegalArgumentException("kline 갭 조회에서 days와 fromMs/toMsExclusive를 함께 보낼 수 없습니다");
        }

        long safeEndMs = BinanceKlineWindow.safeEnd(clock.millis(), INTERVAL);
        long startMs;
        long endMs;
        if (hasFrom) {
            startMs = fromMs;
            endMs = toMsExclusive;
        } else {
            int requestedDays = days == null ? 2 : days;
            if (requestedDays < 1 || requestedDays > 2) {
                throw new IllegalArgumentException("kline 갭 조회 범위는 1일에서 48시간까지만 지원합니다");
            }
            endMs = safeEndMs;
            startMs = endMs - requestedDays * DAY_MS;
        }

        BinanceKlineRangeFetcher.validateRange(startMs, endMs, INTERVAL);
        if (endMs > safeEndMs) {
            throw new IllegalArgumentException("kline 갭 조회 끝 시각은 현재 시각보다 10분 이전이어야 합니다");
        }
        if (endMs - startMs > MAX_RANGE_MS) {
            throw new IllegalArgumentException("kline 갭 조회 범위는 최대 48시간입니다");
        }
        return new long[]{startMs, endMs};
    }

    private List<LinkedHashMap<String, Object>> toGapRows(
            String symbol, String marketType, Set<Long> missing) {
        List<LinkedHashMap<String, Object>> result = new ArrayList<>();
        Long runStart = null;
        Long previous = null;
        int count = 0;
        for (Long value : missing) {
            if (runStart == null) {
                runStart = value;
                count = 1;
            } else if (value - previous == INTERVAL_MS) {
                count++;
            } else {
                result.add(gapRow(symbol, marketType, runStart, previous + INTERVAL_MS, count));
                runStart = value;
                count = 1;
            }
            previous = value;
        }
        if (runStart != null) {
            result.add(gapRow(symbol, marketType, runStart, previous + INTERVAL_MS, count));
        }
        return result;
    }

    private LinkedHashMap<String, Object> gapRow(
            String symbol, String marketType, long startMs, long endMsExclusive, int count) {
        LinkedHashMap<String, Object> row = baseRow(symbol, marketType);
        row.put("gap_start", Timestamp.from(Instant.ofEpochMilli(startMs)));
        row.put("gap_end", Timestamp.from(Instant.ofEpochMilli(endMsExclusive - INTERVAL_MS)));
        row.put("missing_candles", count);
        row.put("gap_start_ms", startMs);
        row.put("gap_end_ms", endMsExclusive);
        row.put("status", "GAP");
        row.put("error", null);
        return row;
    }

    private LinkedHashMap<String, Object> errorRow(String symbol, String marketType, String message) {
        LinkedHashMap<String, Object> row = baseRow(symbol, marketType);
        row.put("gap_start", null);
        row.put("gap_end", null);
        row.put("missing_candles", null);
        row.put("gap_start_ms", null);
        row.put("gap_end_ms", null);
        row.put("status", "ERROR");
        row.put("error", message);
        return row;
    }

    private LinkedHashMap<String, Object> baseRow(String symbol, String marketType) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("symbol", symbol);
        row.put("market_type", marketType);
        return row;
    }

    private String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
