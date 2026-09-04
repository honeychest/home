package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.AggTradeCollectStatus;
import com.chs.springboot.domain.binance.model.BinanceKline;
import com.chs.springboot.domain.binance.model.BinanceKlineInterval;
import com.chs.springboot.domain.binance.model.BinanceKlineTempCandle;
import com.chs.springboot.domain.binance.repository.AggTradeCollectStatusRepository;
import com.chs.springboot.domain.binance.repository.BinanceKlineTempCandleRepository;
import com.chs.springboot.global.redis.LeaderElectionService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BinanceKlineTempSyncServiceTest {

    @Test
    void scheduledSyncSkipsAllWorkWhenInstanceIsNotLeader() {
        LeaderElectionService leaderElectionService = mock(LeaderElectionService.class);
        AggTradeCollectStatusRepository statusRepository = mock(AggTradeCollectStatusRepository.class);
        BinanceKlineTempCandleRepository tempCandleRepository = mock(BinanceKlineTempCandleRepository.class);
        BinanceKlineRestClient restClient = mock(BinanceKlineRestClient.class);
        BinanceKlineTempWriter tempWriter = mock(BinanceKlineTempWriter.class);
        when(leaderElectionService.isLeader()).thenReturn(false);

        new BinanceKlineTempSyncService(
                leaderElectionService, statusRepository, tempCandleRepository, restClient, tempWriter).syncScheduled();

        verifyNoInteractions(statusRepository, tempCandleRepository, restClient);
    }

    @Test
    void syncStartsAfterLastTempCandleAndStoresReturnedRows() {
        LeaderElectionService leaderElectionService = mock(LeaderElectionService.class);
        AggTradeCollectStatusRepository statusRepository = mock(AggTradeCollectStatusRepository.class);
        BinanceKlineTempCandleRepository tempCandleRepository = mock(BinanceKlineTempCandleRepository.class);
        BinanceKlineRestClient restClient = mock(BinanceKlineRestClient.class);
        BinanceKlineTempWriter tempWriter = mock(BinanceKlineTempWriter.class);
        when(statusRepository.findByEnabledTrue()).thenReturn(List.of(status("BTCUSDT", "SPOT")));
        when(tempCandleRepository.findMaxCandleTimeMsBySymbolAndMarketType("BTCUSDT", "SPOT"))
                .thenReturn(Optional.of(60_000L));
        BinanceKline kline = kline(120_000L);
        when(restClient.fetchPage(eq("BTCUSDT"), eq("SPOT"), eq(120_000L), eq(180_000L), eq(BinanceKlineInterval.ONE_MINUTE)))
                .thenReturn(List.of(kline));
        when(tempWriter.insertIgnore(eq("BTCUSDT"), eq("SPOT"), any())).thenReturn(1);

        new BinanceKlineTempSyncService(
                leaderElectionService, statusRepository, tempCandleRepository, restClient, tempWriter)
                .syncNow(309_999L);

        verify(restClient).fetchPage("BTCUSDT", "SPOT", 120_000L, 180_000L, BinanceKlineInterval.ONE_MINUTE);
        verify(tempWriter).insertIgnore(eq("BTCUSDT"), eq("SPOT"), any());
        verify(tempCandleRepository, never()).findMaxCandleTimeMsBySymbolAndMarketType("BTCUSDT", "FUTURES");
    }

    @Test
    void skipsOnlyDuplicateInFlightRequestWhileTheFirstRequestContinues() throws Exception {
        LeaderElectionService leaderElectionService = mock(LeaderElectionService.class);
        AggTradeCollectStatusRepository statusRepository = mock(AggTradeCollectStatusRepository.class);
        BinanceKlineTempCandleRepository tempCandleRepository = mock(BinanceKlineTempCandleRepository.class);
        BinanceKlineRestClient restClient = mock(BinanceKlineRestClient.class);
        BinanceKlineRangeFetcher rangeFetcher = mock(BinanceKlineRangeFetcher.class);
        BinanceKlineTempWriter tempWriter = mock(BinanceKlineTempWriter.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(rangeFetcher.fetch("BTCUSDT", "SPOT", 0L, 60_000L)).thenAnswer(invocation -> {
            entered.countDown();
            release.await(2, TimeUnit.SECONDS);
            return new BinanceKlineRangeFetcher.RangeResult(List.of(kline(0L)), false, 1);
        });
        when(tempWriter.insertIgnore(eq("BTCUSDT"), eq("SPOT"), any())).thenReturn(1);

        BinanceKlineTempSyncService service = new BinanceKlineTempSyncService(
                leaderElectionService, statusRepository, tempCandleRepository, restClient,
                rangeFetcher, tempWriter, new BinanceKlineFiveMinuteAggregator(),
                Clock.fixed(Instant.EPOCH, java.time.ZoneOffset.UTC));
        Thread first = new Thread(() -> service.rangeSync("BTCUSDT", "SPOT", 0L, 60_000L));
        first.start();
        entered.await(1, TimeUnit.SECONDS);

        BinanceKlineTempSyncService.RangeSyncResult second =
                service.rangeSync("BTCUSDT", "SPOT", 0L, 60_000L);

        assertTrue(second.skippedInFlight());
        release.countDown();
        first.join(2_000L);
        verify(tempWriter).insertIgnore(eq("BTCUSDT"), eq("SPOT"), any());
    }

    @Test
    void scheduledTailCapsAnOldRangeAtTheLatest48Hours() {
        LeaderElectionService leaderElectionService = mock(LeaderElectionService.class);
        AggTradeCollectStatusRepository statusRepository = mock(AggTradeCollectStatusRepository.class);
        BinanceKlineTempCandleRepository tempCandleRepository = mock(BinanceKlineTempCandleRepository.class);
        BinanceKlineRestClient restClient = mock(BinanceKlineRestClient.class);
        BinanceKlineTempWriter tempWriter = mock(BinanceKlineTempWriter.class);
        when(statusRepository.findByEnabledTrue()).thenReturn(List.of(status("BTCUSDT", "SPOT")));
        when(tempCandleRepository.findMaxCandleTimeMsBySymbolAndMarketType("BTCUSDT", "SPOT"))
                .thenReturn(Optional.of(0L));

        long nowMs = 100L * BinanceKlineWindow.HOUR_MS + 1_234L;
        long safeEnd = BinanceKlineWindow.safeEnd(nowMs);
        long cappedStart = safeEnd - BinanceKlineRangeFetcher.MAX_RANGE_MS;
        when(restClient.fetchPage(eq("BTCUSDT"), eq("SPOT"), eq(cappedStart), eq(safeEnd), eq(BinanceKlineInterval.ONE_MINUTE)))
                .thenReturn(List.of(kline(cappedStart)));
        when(tempWriter.insertIgnore(eq("BTCUSDT"), eq("SPOT"), any())).thenReturn(1);

        new BinanceKlineTempSyncService(
                leaderElectionService, statusRepository, tempCandleRepository, restClient, tempWriter)
                .syncNow(nowMs);

        verify(restClient).fetchPage("BTCUSDT", "SPOT", cappedStart, safeEnd, BinanceKlineInterval.ONE_MINUTE);
    }

    @Test
    void rangeSyncRejectsRangesLongerThan48Hours() {
        BinanceKlineTempSyncService service = new BinanceKlineTempSyncService(
                mock(LeaderElectionService.class),
                mock(AggTradeCollectStatusRepository.class),
                mock(BinanceKlineTempCandleRepository.class),
                mock(BinanceKlineRestClient.class),
                mock(BinanceKlineTempWriter.class));

        assertThrows(IllegalArgumentException.class, () -> service.rangeSync(
                "BTCUSDT", "SPOT", 0L, BinanceKlineRangeFetcher.MAX_RANGE_MS + 60_000L));
    }

    private AggTradeCollectStatus status(String symbol, String marketType) {
        AggTradeCollectStatus status = new AggTradeCollectStatus();
        status.setSymbol(symbol);
        status.setMarketType(marketType);
        status.setEnabled(true);
        return status;
    }

    private BinanceKline kline(long openTimeMs) {
        return new BinanceKline(
                openTimeMs,
                new BigDecimal("1"),
                new BigDecimal("2"),
                new BigDecimal("0.5"),
                new BigDecimal("1.5"),
                new BigDecimal("10"),
                openTimeMs + 59_999L,
                new BigDecimal("15"),
                3L,
                new BigDecimal("5"),
                new BigDecimal("7.5")
        );
    }
}
