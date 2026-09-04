package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.AggTradeCollectStatus;
import com.chs.springboot.domain.binance.model.BinanceKlineFiveMinute;
import com.chs.springboot.domain.binance.model.BinanceKlineTempCandle;
import com.chs.springboot.domain.binance.repository.AggTradeCollectStatusRepository;
import com.chs.springboot.domain.binance.repository.BinanceKlineTempCandleRepository;
import com.chs.springboot.global.redis.LeaderElectionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Set;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class BinanceKlineTempSyncService {

    private final LeaderElectionService leaderElectionService;
    private final AggTradeCollectStatusRepository statusRepository;
    private final BinanceKlineTempCandleRepository tempCandleRepository;
    private final BinanceKlineRangeFetcher rangeFetcher;
    private final BinanceKlineTempWriter tempWriter;
    private final BinanceKlineFiveMinuteAggregator fiveMinuteAggregator;
    private final Clock clock;
    private final Set<String> inFlightRanges = ConcurrentHashMap.newKeySet();

    @Autowired
    public BinanceKlineTempSyncService(
            LeaderElectionService leaderElectionService,
            AggTradeCollectStatusRepository statusRepository,
            BinanceKlineTempCandleRepository tempCandleRepository,
            BinanceKlineRestClient restClient,
            BinanceKlineTempWriter tempWriter) {
        this(leaderElectionService, statusRepository, tempCandleRepository, restClient,
                new BinanceKlineRangeFetcher(restClient), tempWriter,
                new BinanceKlineFiveMinuteAggregator(), Clock.systemUTC());
    }

    BinanceKlineTempSyncService(
            LeaderElectionService leaderElectionService,
            AggTradeCollectStatusRepository statusRepository,
            BinanceKlineTempCandleRepository tempCandleRepository,
            BinanceKlineRestClient restClient,
            BinanceKlineTempWriter tempWriter,
            BinanceKlineFiveMinuteAggregator fiveMinuteAggregator,
            Clock clock) {
        this(leaderElectionService, statusRepository, tempCandleRepository, restClient,
                new BinanceKlineRangeFetcher(restClient), tempWriter, fiveMinuteAggregator, clock);
    }

    BinanceKlineTempSyncService(
            LeaderElectionService leaderElectionService,
            AggTradeCollectStatusRepository statusRepository,
            BinanceKlineTempCandleRepository tempCandleRepository,
            BinanceKlineRestClient restClient,
            BinanceKlineRangeFetcher rangeFetcher,
            BinanceKlineTempWriter tempWriter,
            BinanceKlineFiveMinuteAggregator fiveMinuteAggregator,
            Clock clock) {
        this.leaderElectionService = leaderElectionService;
        this.statusRepository = statusRepository;
        this.tempCandleRepository = tempCandleRepository;
        this.rangeFetcher = rangeFetcher;
        this.tempWriter = tempWriter;
        this.fiveMinuteAggregator = fiveMinuteAggregator;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${binance.kline.temp.sync.fixed-delay-ms:60000}",
            initialDelayString = "${binance.kline.temp.sync.initial-delay-ms:5000}"
    )
    public void syncScheduled() {
        if (!leaderElectionService.isLeader()) {
            return;
        }
        try {
            syncNow(clock.millis());
        } catch (Exception e) {
            log.error("[BinanceKlineTemp] 동기화 전체 실패: {}", e.getMessage(), e);
        }
    }

    public void syncNow(long nowMs) {
        for (AggTradeCollectStatus status : statusRepository.findByEnabledTrue()) {
            long startedAt = System.currentTimeMillis();
            try {
                syncSymbol(status, nowMs);
                log.info("[BinanceKlineTemp] {} {} 동기화 완료 elapsedMs={}",
                        status.getSymbol(), status.getMarketType(), System.currentTimeMillis() - startedAt);
            } catch (Exception e) {
                log.warn("[BinanceKlineTemp] {} {} 실패(elapsedMs={}): {}",
                        status.getSymbol(), status.getMarketType(),
                        System.currentTimeMillis() - startedAt, e.getMessage());
            }
        }
    }

    @Transactional(readOnly = true)
    public List<BinanceKlineFiveMinute> calculateFiveMinuteCandles(
            String symbol,
            String marketType,
            long fromMs,
            long toMsExclusive) {
        List<BinanceKlineTempCandle> candles = tempCandleRepository
                .findBySymbolAndMarketTypeAndCandleTimeMsGreaterThanEqualAndCandleTimeMsLessThanOrderByCandleTimeMsAsc(
                        symbol, marketType, fromMs, toMsExclusive);
        return fiveMinuteAggregator.aggregate(candles);
    }

    private void syncSymbol(AggTradeCollectStatus status, long nowMs) {
        String symbol = status.getSymbol();
        String marketType = status.getMarketType();
        Long lastCandleTimeMs = tempCandleRepository
                .findMaxCandleTimeMsBySymbolAndMarketType(symbol, marketType)
                .orElse(null);
        BinanceKlineWindow window = BinanceKlineWindow.fromLastCandle(lastCandleTimeMs, nowMs);
        if (window.isEmpty()) {
            return;
        }

        long pageStartMs = window.startTimeMs();
        long endMsExclusive = window.endTimeMsExclusive();
        if (endMsExclusive - pageStartMs > BinanceKlineRangeFetcher.MAX_RANGE_MS) {
            pageStartMs = endMsExclusive - BinanceKlineRangeFetcher.MAX_RANGE_MS;
            log.warn("[BinanceKlineTemp] {} {} tail 범위가 48시간을 넘어 최신 48시간만 자동 동기화합니다. 이전 구간은 관리자 KLINE_1M 백필을 사용하세요",
                    symbol, marketType);
        }
        if (pageStartMs >= endMsExclusive) {
            return;
        }
        rangeSync(symbol, marketType, pageStartMs, endMsExclusive);
    }

    /**
     * 지정한 1분 범위를 Binance에서 읽어 임시 테이블에 add-only로 저장한다.
     * in-flight 가드는 중복 HTTP 호출을 줄이기 위한 효율 최적화이며 정합성은 DB UK가 보장한다.
     */
    public RangeSyncResult rangeSync(String symbol, String marketType, long fromMs, long toMsExclusive) {
        BinanceKlineRangeFetcher.validateBoundedRange(fromMs, toMsExclusive);
        String key = symbol + "|" + marketType;
        if (!inFlightRanges.add(key)) {
            log.info("[BinanceKlineTemp] {} 동기화가 이미 실행 중이라 건너뜁니다", key);
            return new RangeSyncResult(0, 0, true, false);
        }
        try {
            BinanceKlineRangeFetcher.RangeResult fetched = rangeFetcher.fetch(
                    symbol, marketType, fromMs, toMsExclusive);
            if (fetched.firstPageEmpty()) {
                return new RangeSyncResult(0, fetched.pages(), false, true);
            }
            int inserted = tempWriter.insertIgnore(symbol, marketType, fetched.klines());
            return new RangeSyncResult(inserted, fetched.pages(), false, false);
        } finally {
            inFlightRanges.remove(key);
        }
    }

    public record RangeSyncResult(
            int inserted,
            int pages,
            boolean skippedInFlight,
            boolean firstPageEmpty) {
    }
}
