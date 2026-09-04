package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.AggTradeCollectStatus;
import com.chs.springboot.domain.binance.model.BinanceKline;
import com.chs.springboot.domain.binance.model.BinanceKline5m;
import com.chs.springboot.domain.binance.model.BinanceKlineInterval;
import com.chs.springboot.domain.binance.repository.AggTradeCollectStatusRepository;
import com.chs.springboot.domain.binance.repository.BinanceKline5mRepository;
import com.chs.springboot.global.redis.LeaderElectionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BinanceKline5mSyncServiceTest {

    private static final long INTERVAL_MS = BinanceKlineInterval.FIVE_MINUTES.intervalMs();
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.ofEpochMilli(200L * BinanceKlineRangeFetcher.MAX_RANGE_MS + 12_345L), ZoneOffset.UTC);

    @Test
    void scheduledSyncSkipsAllWorkWhenInstanceIsNotLeader() {
        LeaderElectionService leaderElectionService = mock(LeaderElectionService.class);
        AggTradeCollectStatusRepository statusRepository = mock(AggTradeCollectStatusRepository.class);
        BinanceKline5mRepository candleRepository = mock(BinanceKline5mRepository.class);
        BinanceKlineRangeFetcher rangeFetcher = mock(BinanceKlineRangeFetcher.class);
        BinanceKline5mWriter writer = mock(BinanceKline5mWriter.class);
        when(leaderElectionService.isLeader()).thenReturn(false);

        new BinanceKline5mSyncService(
                leaderElectionService, statusRepository, candleRepository, rangeFetcher, writer, FIXED_CLOCK)
                .syncScheduled();

        verifyNoInteractions(statusRepository, candleRepository, rangeFetcher, writer);
    }

    @Test
    void scheduledSyncSurvivesStatusRepositoryFailureWithoutPropagating() {
        LeaderElectionService leaderElectionService = mock(LeaderElectionService.class);
        AggTradeCollectStatusRepository statusRepository = mock(AggTradeCollectStatusRepository.class);
        BinanceKline5mRepository candleRepository = mock(BinanceKline5mRepository.class);
        BinanceKlineRangeFetcher rangeFetcher = mock(BinanceKlineRangeFetcher.class);
        BinanceKline5mWriter writer = mock(BinanceKline5mWriter.class);
        when(leaderElectionService.isLeader()).thenReturn(true);
        when(statusRepository.findByEnabledTrue()).thenThrow(new RuntimeException("DB timeout"));

        // 예외가 여기서 밖으로 전파되면 실패 — @Scheduled 메서드가 통째로 죽지 않아야 한다.
        new BinanceKline5mSyncService(
                leaderElectionService, statusRepository, candleRepository, rangeFetcher, writer, FIXED_CLOCK)
                .syncScheduled();

        verifyNoInteractions(candleRepository, rangeFetcher, writer);
    }

    @Test
    void manualRefillThrowsWhenInstanceIsNotLeader() {
        LeaderElectionService leaderElectionService = mock(LeaderElectionService.class);
        when(leaderElectionService.isLeader()).thenReturn(false);
        BinanceKline5mSyncService service = new BinanceKline5mSyncService(
                leaderElectionService, mock(AggTradeCollectStatusRepository.class),
                mock(BinanceKline5mRepository.class), mock(BinanceKlineRangeFetcher.class),
                mock(BinanceKline5mWriter.class), FIXED_CLOCK);

        assertThrowsIllegalState(() -> service.manualRefill("BTCUSDT", "SPOT", FIXED_CLOCK.millis()));
    }

    @Test
    void manualBackfillRangeThrowsWhenInstanceIsNotLeader() {
        LeaderElectionService leaderElectionService = mock(LeaderElectionService.class);
        when(leaderElectionService.isLeader()).thenReturn(false);
        BinanceKline5mSyncService service = new BinanceKline5mSyncService(
                leaderElectionService, mock(AggTradeCollectStatusRepository.class),
                mock(BinanceKline5mRepository.class), mock(BinanceKlineRangeFetcher.class),
                mock(BinanceKline5mWriter.class), FIXED_CLOCK);

        assertThrowsIllegalState(() -> service.manualBackfillRange("BTCUSDT", "SPOT", 0L, INTERVAL_MS));
    }

    @Test
    void manualBackfillRangeRejectsRangesLongerThan48Hours() {
        LeaderElectionService leaderElectionService = mock(LeaderElectionService.class);
        when(leaderElectionService.isLeader()).thenReturn(true);
        BinanceKline5mSyncService service = new BinanceKline5mSyncService(
                leaderElectionService, mock(AggTradeCollectStatusRepository.class),
                mock(BinanceKline5mRepository.class), mock(BinanceKlineRangeFetcher.class),
                mock(BinanceKline5mWriter.class), FIXED_CLOCK);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                service.manualBackfillRange("BTCUSDT", "SPOT", 0L, BinanceKlineRangeFetcher.MAX_RANGE_MS + INTERVAL_MS));
    }

    @Test
    void manualBackfillRangeRejectsRangeNotOnFiveMinuteBoundary() {
        LeaderElectionService leaderElectionService = mock(LeaderElectionService.class);
        when(leaderElectionService.isLeader()).thenReturn(true);
        BinanceKline5mSyncService service = new BinanceKline5mSyncService(
                leaderElectionService, mock(AggTradeCollectStatusRepository.class),
                mock(BinanceKline5mRepository.class), mock(BinanceKlineRangeFetcher.class),
                mock(BinanceKline5mWriter.class), FIXED_CLOCK);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                service.manualBackfillRange("BTCUSDT", "SPOT", 60_000L, 360_000L)); // 1분 경계 — 5분 아님
    }

    @Test
    void manualBackfillRangeRejectsEndTimeAfterSafeEnd() {
        LeaderElectionService leaderElectionService = mock(LeaderElectionService.class);
        when(leaderElectionService.isLeader()).thenReturn(true);
        BinanceKline5mSyncService service = new BinanceKline5mSyncService(
                leaderElectionService, mock(AggTradeCollectStatusRepository.class),
                mock(BinanceKline5mRepository.class), mock(BinanceKlineRangeFetcher.class),
                mock(BinanceKline5mWriter.class), FIXED_CLOCK);

        long safeEnd = BinanceKlineWindow.safeEnd(FIXED_CLOCK.millis(), BinanceKlineInterval.FIVE_MINUTES);
        // 아직 완료되지 않았을 수 있는 구간(safeEnd 이후)을 백필하려 하면 거부해야 한다.
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                service.manualBackfillRange("BTCUSDT", "SPOT", safeEnd - INTERVAL_MS, safeEnd + INTERVAL_MS));
    }

    @Test
    void manualBackfillRangeReportsSkippedInFlightInsteadOfFalseSuccess() throws InterruptedException {
        LeaderElectionService leaderElectionService = mock(LeaderElectionService.class);
        when(leaderElectionService.isLeader()).thenReturn(true);
        BinanceKline5mRepository candleRepository = mock(BinanceKline5mRepository.class);
        BinanceKlineRangeFetcher rangeFetcher = mock(BinanceKlineRangeFetcher.class);
        BinanceKline5mWriter writer = mock(BinanceKline5mWriter.class);

        long fromMs = 1_000L * INTERVAL_MS;
        long toMsExclusive = fromMs + INTERVAL_MS;
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(rangeFetcher.fetch(eq("BTCUSDT"), eq("SPOT"), anyLong(), anyLong())).thenAnswer(invocation -> {
            entered.countDown();
            release.await(2, java.util.concurrent.TimeUnit.SECONDS);
            return new BinanceKlineRangeFetcher.RangeResult(List.of(kline(fromMs)), false, 1);
        });

        BinanceKline5mSyncService service = new BinanceKline5mSyncService(
                leaderElectionService, mock(AggTradeCollectStatusRepository.class), candleRepository,
                rangeFetcher, writer, FIXED_CLOCK);
        Thread first = new Thread(() -> service.manualBackfillRange("BTCUSDT", "SPOT", fromMs, toMsExclusive));
        first.start();
        entered.await(1, java.util.concurrent.TimeUnit.SECONDS);

        BinanceKline5mSyncService.RefillResult second =
                service.manualBackfillRange("BTCUSDT", "SPOT", fromMs, toMsExclusive);

        assertTrue(second.skippedInFlight());
        release.countDown();
        first.join(2_000L);
    }

    @Test
    void manualBackfillRangeFillsAnArbitraryPastRange() {
        LeaderElectionService leaderElectionService = mock(LeaderElectionService.class);
        when(leaderElectionService.isLeader()).thenReturn(true);
        BinanceKline5mRepository candleRepository = mock(BinanceKline5mRepository.class);
        BinanceKlineRangeFetcher rangeFetcher = mock(BinanceKlineRangeFetcher.class);
        BinanceKline5mWriter writer = mock(BinanceKline5mWriter.class);

        long fromMs = 1_000L * INTERVAL_MS;
        long toMsExclusive = fromMs + 3 * INTERVAL_MS;
        when(candleRepository.findBySymbolAndMarketTypeAndCandleTimeMsGreaterThanEqualAndCandleTimeMsLessThanOrderByCandleTimeMsAsc(
                eq("BTCUSDT"), eq("SPOT"), anyLong(), anyLong()))
                .thenReturn(List.of());
        List<BinanceKline> filled = List.of(
                kline(fromMs), kline(fromMs + INTERVAL_MS), kline(fromMs + 2 * INTERVAL_MS));
        when(rangeFetcher.fetch("BTCUSDT", "SPOT", fromMs, toMsExclusive))
                .thenReturn(new BinanceKlineRangeFetcher.RangeResult(filled, false, 1));
        when(writer.insertIgnore(eq("BTCUSDT"), eq("SPOT"), any())).thenReturn(3);

        BinanceKline5mSyncService.RefillResult result = new BinanceKline5mSyncService(
                leaderElectionService, mock(AggTradeCollectStatusRepository.class), candleRepository,
                rangeFetcher, writer, FIXED_CLOCK)
                .manualBackfillRange("BTCUSDT", "SPOT", fromMs, toMsExclusive);

        assertEquals(3, result.expected());
        assertEquals(3, result.fetched());
        assertEquals(3, result.inserted());
        verify(rangeFetcher).fetch("BTCUSDT", "SPOT", fromMs, toMsExclusive);
    }

    @Test
    void refillNowFillsASingleGapAndReportsFullAccounting() {
        LeaderElectionService leaderElectionService = mock(LeaderElectionService.class);
        when(leaderElectionService.isLeader()).thenReturn(true);
        BinanceKline5mRepository candleRepository = mock(BinanceKline5mRepository.class);
        BinanceKlineRangeFetcher rangeFetcher = mock(BinanceKlineRangeFetcher.class);
        BinanceKline5mWriter writer = mock(BinanceKline5mWriter.class);

        long nowMs = FIXED_CLOCK.millis();
        long toMsExclusive = BinanceKlineWindow.safeEnd(nowMs, BinanceKlineInterval.FIVE_MINUTES);
        long fromMs = toMsExclusive - BinanceKlineRangeFetcher.MAX_RANGE_MS;
        long gapStart = fromMs + 100 * INTERVAL_MS;
        Set<Long> gap = Set.of(gapStart, gapStart + INTERVAL_MS, gapStart + 2 * INTERVAL_MS);

        List<BinanceKline5m> before = candlesExcept(fromMs, toMsExclusive, gap);
        List<BinanceKline5m> after = candlesExcept(fromMs, toMsExclusive, Set.of());
        when(candleRepository.findBySymbolAndMarketTypeAndCandleTimeMsGreaterThanEqualAndCandleTimeMsLessThanOrderByCandleTimeMsAsc(
                eq("BTCUSDT"), eq("SPOT"), anyLong(), anyLong()))
                .thenReturn(before, after);

        List<BinanceKline> filledKlines = List.of(
                kline(gapStart), kline(gapStart + INTERVAL_MS), kline(gapStart + 2 * INTERVAL_MS));
        when(rangeFetcher.fetch("BTCUSDT", "SPOT", gapStart, gapStart + 3 * INTERVAL_MS))
                .thenReturn(new BinanceKlineRangeFetcher.RangeResult(filledKlines, false, 1));
        when(writer.insertIgnore(eq("BTCUSDT"), eq("SPOT"), any())).thenReturn(3);

        BinanceKline5mSyncService.RefillResult result = new BinanceKline5mSyncService(
                leaderElectionService, mock(AggTradeCollectStatusRepository.class), candleRepository,
                rangeFetcher, writer, FIXED_CLOCK)
                .refillNow("BTCUSDT", "SPOT", nowMs);

        assertEquals(before.size() + 3, result.expected());
        assertEquals(3, result.fetched());
        assertEquals(3, result.inserted());
        assertEquals(after.size(), result.presentAfter());
        assertEquals(0, result.remainingGap());
        assertFalse(result.leaderLostMidRun());
        verify(writer).insertIgnore("BTCUSDT", "SPOT", filledKlines);
    }

    @Test
    void refillNowStopsRemainingRangesWhenLeadershipIsLostMidRun() {
        LeaderElectionService leaderElectionService = mock(LeaderElectionService.class);
        // 최초 진입 확인 없음(패키지 전용 refillNow는 leader 검사를 range마다만 한다) — 첫 range 처리 전 true, 두 번째 range 전 false
        when(leaderElectionService.isLeader()).thenReturn(true, false);
        BinanceKline5mRepository candleRepository = mock(BinanceKline5mRepository.class);
        BinanceKlineRangeFetcher rangeFetcher = mock(BinanceKlineRangeFetcher.class);
        BinanceKline5mWriter writer = mock(BinanceKline5mWriter.class);

        long nowMs = FIXED_CLOCK.millis();
        long toMsExclusive = BinanceKlineWindow.safeEnd(nowMs, BinanceKlineInterval.FIVE_MINUTES);
        long fromMs = toMsExclusive - BinanceKlineRangeFetcher.MAX_RANGE_MS;
        long firstGap = fromMs + 50 * INTERVAL_MS;
        long secondGap = fromMs + 500 * INTERVAL_MS;
        Set<Long> gaps = Set.of(firstGap, secondGap);

        when(candleRepository.findBySymbolAndMarketTypeAndCandleTimeMsGreaterThanEqualAndCandleTimeMsLessThanOrderByCandleTimeMsAsc(
                eq("BTCUSDT"), eq("SPOT"), anyLong(), anyLong()))
                .thenReturn(candlesExcept(fromMs, toMsExclusive, gaps));
        when(rangeFetcher.fetch(eq("BTCUSDT"), eq("SPOT"), anyLong(), anyLong()))
                .thenReturn(new BinanceKlineRangeFetcher.RangeResult(List.of(kline(firstGap)), false, 1));
        when(writer.insertIgnore(eq("BTCUSDT"), eq("SPOT"), any())).thenReturn(1);

        BinanceKline5mSyncService.RefillResult result = new BinanceKline5mSyncService(
                leaderElectionService, mock(AggTradeCollectStatusRepository.class), candleRepository,
                rangeFetcher, writer, FIXED_CLOCK)
                .refillNow("BTCUSDT", "SPOT", nowMs);

        assertTrue(result.leaderLostMidRun());
        verify(rangeFetcher, times(1)).fetch(eq("BTCUSDT"), eq("SPOT"), anyLong(), anyLong());
    }

    @Test
    void refillNowSkipsWriteWhenLeadershipIsLostAfterFetchButBeforeWrite() {
        LeaderElectionService leaderElectionService = mock(LeaderElectionService.class);
        // range 시작 전 확인 true, fetch 완료 후 쓰기 직전 확인 false
        when(leaderElectionService.isLeader()).thenReturn(true, false);
        BinanceKline5mRepository candleRepository = mock(BinanceKline5mRepository.class);
        BinanceKlineRangeFetcher rangeFetcher = mock(BinanceKlineRangeFetcher.class);
        BinanceKline5mWriter writer = mock(BinanceKline5mWriter.class);

        long nowMs = FIXED_CLOCK.millis();
        long toMsExclusive = BinanceKlineWindow.safeEnd(nowMs, BinanceKlineInterval.FIVE_MINUTES);
        long fromMs = toMsExclusive - BinanceKlineRangeFetcher.MAX_RANGE_MS;
        long gapStart = fromMs + 10 * INTERVAL_MS;

        when(candleRepository.findBySymbolAndMarketTypeAndCandleTimeMsGreaterThanEqualAndCandleTimeMsLessThanOrderByCandleTimeMsAsc(
                eq("BTCUSDT"), eq("SPOT"), anyLong(), anyLong()))
                .thenReturn(candlesExcept(fromMs, toMsExclusive, Set.of(gapStart)));
        when(rangeFetcher.fetch("BTCUSDT", "SPOT", gapStart, gapStart + INTERVAL_MS))
                .thenReturn(new BinanceKlineRangeFetcher.RangeResult(List.of(kline(gapStart)), false, 1));

        BinanceKline5mSyncService.RefillResult result = new BinanceKline5mSyncService(
                leaderElectionService, mock(AggTradeCollectStatusRepository.class), candleRepository,
                rangeFetcher, writer, FIXED_CLOCK)
                .refillNow("BTCUSDT", "SPOT", nowMs);

        assertTrue(result.leaderLostMidRun());
        assertEquals(0, result.inserted());
        verify(writer, never()).insertIgnore(anyString(), anyString(), any());
    }

    @Test
    void refillNowRetriesOnTooManyRequestsThenSucceeds() {
        LeaderElectionService leaderElectionService = mock(LeaderElectionService.class);
        when(leaderElectionService.isLeader()).thenReturn(true);
        BinanceKline5mRepository candleRepository = mock(BinanceKline5mRepository.class);
        BinanceKlineRangeFetcher rangeFetcher = mock(BinanceKlineRangeFetcher.class);
        BinanceKline5mWriter writer = mock(BinanceKline5mWriter.class);

        long nowMs = FIXED_CLOCK.millis();
        long toMsExclusive = BinanceKlineWindow.safeEnd(nowMs, BinanceKlineInterval.FIVE_MINUTES);
        long fromMs = toMsExclusive - BinanceKlineRangeFetcher.MAX_RANGE_MS;
        long gapStart = fromMs + 10 * INTERVAL_MS;

        when(candleRepository.findBySymbolAndMarketTypeAndCandleTimeMsGreaterThanEqualAndCandleTimeMsLessThanOrderByCandleTimeMsAsc(
                eq("BTCUSDT"), eq("SPOT"), anyLong(), anyLong()))
                .thenReturn(candlesExcept(fromMs, toMsExclusive, Set.of(gapStart)));
        when(rangeFetcher.fetch("BTCUSDT", "SPOT", gapStart, gapStart + INTERVAL_MS))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", null, null, null))
                .thenReturn(new BinanceKlineRangeFetcher.RangeResult(List.of(kline(gapStart)), false, 1));
        when(writer.insertIgnore(eq("BTCUSDT"), eq("SPOT"), any())).thenReturn(1);

        BinanceKline5mSyncService.RefillResult result = new BinanceKline5mSyncService(
                leaderElectionService, mock(AggTradeCollectStatusRepository.class), candleRepository,
                rangeFetcher, writer, FIXED_CLOCK)
                .refillNow("BTCUSDT", "SPOT", nowMs);

        assertEquals(1, result.fetched());
        verify(rangeFetcher, times(2)).fetch("BTCUSDT", "SPOT", gapStart, gapStart + INTERVAL_MS);
    }

    @Test
    void refillNowGivesUpImmediatelyOnNonRetryableClientError() {
        LeaderElectionService leaderElectionService = mock(LeaderElectionService.class);
        when(leaderElectionService.isLeader()).thenReturn(true);
        BinanceKline5mRepository candleRepository = mock(BinanceKline5mRepository.class);
        BinanceKlineRangeFetcher rangeFetcher = mock(BinanceKlineRangeFetcher.class);
        BinanceKline5mWriter writer = mock(BinanceKline5mWriter.class);

        long nowMs = FIXED_CLOCK.millis();
        long toMsExclusive = BinanceKlineWindow.safeEnd(nowMs, BinanceKlineInterval.FIVE_MINUTES);
        long fromMs = toMsExclusive - BinanceKlineRangeFetcher.MAX_RANGE_MS;
        long gapStart = fromMs + 10 * INTERVAL_MS;

        when(candleRepository.findBySymbolAndMarketTypeAndCandleTimeMsGreaterThanEqualAndCandleTimeMsLessThanOrderByCandleTimeMsAsc(
                eq("BTCUSDT"), eq("SPOT"), anyLong(), anyLong()))
                .thenReturn(candlesExcept(fromMs, toMsExclusive, Set.of(gapStart)));
        when(rangeFetcher.fetch("BTCUSDT", "SPOT", gapStart, gapStart + INTERVAL_MS))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.BAD_REQUEST, "Bad Request", null, null, null));

        BinanceKline5mSyncService.RefillResult result = new BinanceKline5mSyncService(
                leaderElectionService, mock(AggTradeCollectStatusRepository.class), candleRepository,
                rangeFetcher, writer, FIXED_CLOCK)
                .refillNow("BTCUSDT", "SPOT", nowMs);

        assertEquals(0, result.fetched());
        assertEquals(1, result.remainingGap());
        verify(rangeFetcher, times(1)).fetch("BTCUSDT", "SPOT", gapStart, gapStart + INTERVAL_MS);
        verify(writer, never()).insertIgnore(anyString(), anyString(), any());
    }

    @Test
    void refillNowCapsRangesAttemptedPerCycle() {
        LeaderElectionService leaderElectionService = mock(LeaderElectionService.class);
        when(leaderElectionService.isLeader()).thenReturn(true);
        BinanceKline5mRepository candleRepository = mock(BinanceKline5mRepository.class);
        BinanceKlineRangeFetcher rangeFetcher = mock(BinanceKlineRangeFetcher.class);
        BinanceKline5mWriter writer = mock(BinanceKline5mWriter.class);

        long nowMs = FIXED_CLOCK.millis();
        long toMsExclusive = BinanceKlineWindow.safeEnd(nowMs, BinanceKlineInterval.FIVE_MINUTES);
        long fromMs = toMsExclusive - BinanceKlineRangeFetcher.MAX_RANGE_MS;
        // 격리된 단일 gap 25개(6칸 간격) — 예산(20)보다 많은 gap-range를 만든다.
        Set<Long> gaps = new HashSet<>();
        for (int i = 0; i < 25; i++) {
            gaps.add(fromMs + (10 + i * 6L) * INTERVAL_MS);
        }

        when(candleRepository.findBySymbolAndMarketTypeAndCandleTimeMsGreaterThanEqualAndCandleTimeMsLessThanOrderByCandleTimeMsAsc(
                eq("BTCUSDT"), eq("SPOT"), anyLong(), anyLong()))
                .thenReturn(candlesExcept(fromMs, toMsExclusive, gaps));
        when(rangeFetcher.fetch(eq("BTCUSDT"), eq("SPOT"), anyLong(), anyLong()))
                .thenAnswer(invocation -> new BinanceKlineRangeFetcher.RangeResult(
                        List.of(kline(invocation.getArgument(2))), false, 1));
        when(writer.insertIgnore(eq("BTCUSDT"), eq("SPOT"), any())).thenReturn(1);

        BinanceKline5mSyncService.RefillResult result = new BinanceKline5mSyncService(
                leaderElectionService, mock(AggTradeCollectStatusRepository.class), candleRepository,
                rangeFetcher, writer, FIXED_CLOCK)
                .refillNow("BTCUSDT", "SPOT", nowMs);

        verify(rangeFetcher, times(BinanceKline5mSyncService.MAX_RANGES_PER_CYCLE))
                .fetch(eq("BTCUSDT"), eq("SPOT"), anyLong(), anyLong());
        assertFalse(result.leaderLostMidRun());
    }

    private List<BinanceKline5m> candlesExcept(long fromMs, long toMsExclusive, Set<Long> excluded) {
        List<BinanceKline5m> candles = new ArrayList<>();
        for (long t = fromMs; t < toMsExclusive; t += INTERVAL_MS) {
            if (excluded.contains(t)) {
                continue;
            }
            BinanceKline5m candle = new BinanceKline5m();
            candle.setCandleTimeMs(t);
            candles.add(candle);
        }
        return candles;
    }

    private BinanceKline kline(long openTimeMs) {
        return new BinanceKline(openTimeMs, new BigDecimal("10"), new BigDecimal("12"),
                new BigDecimal("9"), new BigDecimal("11"), new BigDecimal("2"),
                openTimeMs + INTERVAL_MS - 1, new BigDecimal("20"), 3L,
                new BigDecimal("1"), new BigDecimal("10"));
    }

    private void assertThrowsIllegalState(Runnable runnable) {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, runnable::run);
    }
}
