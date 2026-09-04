package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.AggTradeCollectStatus;
import com.chs.springboot.domain.binance.model.BinanceKline5m;
import com.chs.springboot.domain.binance.model.BinanceKlineInterval;
import com.chs.springboot.domain.binance.repository.AggTradeCollectStatusRepository;
import com.chs.springboot.domain.binance.repository.BinanceKline5mRepository;
import com.chs.springboot.global.redis.LeaderElectionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * binance_kline_5m(canonical) 정기 갱신 — "마지막 이후 채우기"(정상 진행)와 "구멍 메우기"
 * (장애 복구)를 같은 경로로 합친 리필 방식(kline-temp-retire-plan.md 참고). 매 회차마다
 * 최근 48시간의 "있어야 할 5분봉 시각 집합"과 DB에 있는 집합을 비교해 빠진 구간만 다시
 * 채운다 — 정상 tail도 결국 "가장 최근 구간의 gap"이라 별도 경로가 필요 없다.
 */
@Slf4j
@Service
public class BinanceKline5mSyncService {

    /** 한 회차(스케줄 tick)에서 시도할 최대 gap-range 수 — REST 호출 폭주 방지. */
    static final int MAX_RANGES_PER_CYCLE = 20;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_BASE_DELAY_MS = 500L;

    private final LeaderElectionService leaderElectionService;
    private final AggTradeCollectStatusRepository statusRepository;
    private final BinanceKline5mRepository candleRepository;
    private final BinanceKlineRangeFetcher rangeFetcher;
    private final BinanceKline5mWriter writer;
    private final Clock clock;
    private final Set<String> inFlightSymbols = ConcurrentHashMap.newKeySet();

    @Autowired
    public BinanceKline5mSyncService(
            LeaderElectionService leaderElectionService,
            AggTradeCollectStatusRepository statusRepository,
            BinanceKline5mRepository candleRepository,
            BinanceKlineRestClient restClient,
            BinanceKline5mWriter writer) {
        this(leaderElectionService, statusRepository, candleRepository,
                new BinanceKlineRangeFetcher(restClient, BinanceKlineInterval.FIVE_MINUTES),
                writer, Clock.systemUTC());
    }

    BinanceKline5mSyncService(
            LeaderElectionService leaderElectionService,
            AggTradeCollectStatusRepository statusRepository,
            BinanceKline5mRepository candleRepository,
            BinanceKlineRangeFetcher rangeFetcher,
            BinanceKline5mWriter writer,
            Clock clock) {
        this.leaderElectionService = leaderElectionService;
        this.statusRepository = statusRepository;
        this.candleRepository = candleRepository;
        this.rangeFetcher = rangeFetcher;
        this.writer = writer;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${binance.kline.5m.sync.fixed-delay-ms:60000}",
            initialDelayString = "${binance.kline.5m.sync.initial-delay-ms:5000}"
    )
    public void syncScheduled() {
        if (!leaderElectionService.isLeader()) {
            return;
        }
        List<AggTradeCollectStatus> statuses;
        try {
            statuses = statusRepository.findByEnabledTrue();
        } catch (Exception e) {
            log.warn("[BinanceKline5m] 대상 목록 조회 실패: {}", e.getMessage());
            return;
        }
        for (AggTradeCollectStatus status : statuses) {
            long startedAt = System.currentTimeMillis();
            try {
                RefillResult result = refillNow(status.getSymbol(), status.getMarketType(), clock.millis());
                log.info("[BinanceKline5m] {} {} 리필 완료 elapsedMs={} expected={} fetched={} inserted={} "
                                + "presentAfter={} remainingGap={} leaderLostMidRun={}",
                        status.getSymbol(), status.getMarketType(), System.currentTimeMillis() - startedAt,
                        result.expected(), result.fetched(), result.inserted(),
                        result.presentAfter(), result.remainingGap(), result.leaderLostMidRun());
            } catch (Exception e) {
                log.warn("[BinanceKline5m] {} {} 리필 실패(elapsedMs={}): {}",
                        status.getSymbol(), status.getMarketType(),
                        System.currentTimeMillis() - startedAt, e.getMessage());
            }
        }
    }

    /**
     * 관리자 수동 트리거 전용 진입점 — 팔로워 인스턴스가 직접 writer를 호출하지 못하게
     * 리더 여부를 먼저 확인한다(비리더면 예외 — 실제 리더로 전달할지는 호출부가 결정).
     */
    public RefillResult manualRefill(String symbol, String marketType, long nowMs) {
        if (!leaderElectionService.isLeader()) {
            throw new IllegalStateException(
                    "이 인스턴스는 리더가 아니라 binance_kline_5m 리필을 직접 실행할 수 없습니다");
        }
        return refillNow(symbol, marketType, nowMs);
    }

    /**
     * 최근 48시간의 "있어야 할" 5분봉 시각 집합과 DB에 있는 집합을 비교해 빠진 구간만
     * 다시 채운다. in-flight 가드는 중복 HTTP 호출을 줄이기 위한 효율 최적화이며
     * 정합성은 DB UK가 보장한다.
     */
    RefillResult refillNow(String symbol, String marketType, long nowMs) {
        long intervalMs = BinanceKlineInterval.FIVE_MINUTES.intervalMs();
        long toMsExclusive = BinanceKlineWindow.safeEnd(nowMs, BinanceKlineInterval.FIVE_MINUTES);
        long fromMs = Math.max(0L, toMsExclusive - BinanceKlineRangeFetcher.MAX_RANGE_MS);
        if (toMsExclusive <= fromMs) {
            return new RefillResult(0, 0, 0, 0, 0, false);
        }

        String key = symbol + "|" + marketType;
        if (!inFlightSymbols.add(key)) {
            log.info("[BinanceKline5m] {} 리필이 이미 실행 중이라 건너뜁니다", key);
            return new RefillResult(0, 0, 0, 0, 0, false);
        }
        try {
            TreeSet<Long> expected = expectedTimes(fromMs, toMsExclusive, intervalMs);
            TreeSet<Long> stored = storedTimes(symbol, marketType, fromMs, toMsExclusive);
            TreeSet<Long> missing = new TreeSet<>(expected);
            missing.removeAll(stored);

            int fetched = 0;
            int inserted = 0;
            boolean leaderLostMidRun = false;
            int rangesAttempted = 0;
            for (long[] range : mergedRanges(missing, intervalMs)) {
                if (rangesAttempted >= MAX_RANGES_PER_CYCLE) {
                    log.info("[BinanceKline5m] {} 회차당 최대 gap-range 수({})에 도달해 나머지는 다음 회차로 미룹니다",
                            key, MAX_RANGES_PER_CYCLE);
                    break;
                }
                if (!leaderElectionService.isLeader()) {
                    leaderLostMidRun = true;
                    break;
                }
                rangesAttempted++;
                BinanceKlineRangeFetcher.RangeResult result = fetchWithRetry(symbol, marketType, range[0], range[1]);
                if (result == null || result.firstPageEmpty()) {
                    continue; // 이번 회차엔 못 채움 — remaining_gap에 남아 다음 회차가 재시도
                }
                fetched += result.klines().size();
                // REST 왕복(재시도 포함) 동안 리더가 바뀌었을 수 있다 — 쓰기 직전에 다시 확인해
                // 이미 리더가 아닌 인스턴스가 그대로 insertIgnore를 실행하지 않게 한다.
                if (!leaderElectionService.isLeader()) {
                    leaderLostMidRun = true;
                    break;
                }
                inserted += writer.insertIgnore(symbol, marketType, result.klines());
            }

            TreeSet<Long> presentAfter = storedTimes(symbol, marketType, fromMs, toMsExclusive);
            int remainingGap = expected.size() - presentAfter.size();
            return new RefillResult(expected.size(), fetched, inserted, presentAfter.size(), remainingGap, leaderLostMidRun);
        } finally {
            inFlightSymbols.remove(key);
        }
    }

    private TreeSet<Long> expectedTimes(long fromMs, long toMsExclusive, long intervalMs) {
        TreeSet<Long> times = new TreeSet<>();
        for (long t = fromMs; t < toMsExclusive; t += intervalMs) {
            times.add(t);
        }
        return times;
    }

    private TreeSet<Long> storedTimes(String symbol, String marketType, long fromMs, long toMsExclusive) {
        TreeSet<Long> times = new TreeSet<>();
        for (BinanceKline5m candle : candleRepository
                .findBySymbolAndMarketTypeAndCandleTimeMsGreaterThanEqualAndCandleTimeMsLessThanOrderByCandleTimeMsAsc(
                        symbol, marketType, fromMs, toMsExclusive)) {
            times.add(candle.getCandleTimeMs());
        }
        return times;
    }

    /** 인접한 빠진 시각을 하나의 [start, endExclusive) 범위로 병합한다(회차당 REST 호출 수를 줄인다). */
    private List<long[]> mergedRanges(TreeSet<Long> missing, long intervalMs) {
        List<long[]> ranges = new ArrayList<>();
        Long runStart = null;
        Long previous = null;
        for (Long time : missing) {
            if (runStart == null) {
                runStart = time;
            } else if (time - previous != intervalMs) {
                ranges.add(new long[]{runStart, previous + intervalMs});
                runStart = time;
            }
            previous = time;
        }
        if (runStart != null) {
            ranges.add(new long[]{runStart, previous + intervalMs});
        }
        return ranges;
    }

    /** 429/5xx만 재시도한다 — 그 외 오류(4xx)는 이 회차에 재시도해도 성공할 리 없어 즉시 포기한다. */
    private BinanceKlineRangeFetcher.RangeResult fetchWithRetry(
            String symbol, String marketType, long fromMs, long toMsExclusive) {
        long delayMs = RETRY_BASE_DELAY_MS;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return rangeFetcher.fetch(symbol, marketType, fromMs, toMsExclusive);
            } catch (HttpStatusCodeException e) {
                int status = e.getStatusCode().value();
                boolean retryable = status == 429 || status >= 500;
                if (!retryable || attempt == MAX_RETRIES) {
                    log.warn("[BinanceKline5m] {} {} [{},{}) 요청 실패(status={}, attempt={}): {}",
                            symbol, marketType, fromMs, toMsExclusive, status, attempt, e.getMessage());
                    return null;
                }
                if (!sleep(delayMs)) {
                    return null; // 종료 신호(interrupt) — 재시도로 다음 REST 호출을 만들지 않는다
                }
                delayMs *= 2;
            } catch (Exception e) {
                log.warn("[BinanceKline5m] {} {} [{},{}) 요청 실패(attempt={}): {}",
                        symbol, marketType, fromMs, toMsExclusive, attempt, e.getMessage());
                return null;
            }
        }
        return null;
    }

    /** @return 끝까지 잤으면 true, 중간에 interrupt(종료 신호)를 받았으면 false. */
    private boolean sleep(long delayMs) {
        try {
            Thread.sleep(delayMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public record RefillResult(
            int expected,
            int fetched,
            int inserted,
            int presentAfter,
            int remainingGap,
            boolean leaderLostMidRun) {
    }
}
