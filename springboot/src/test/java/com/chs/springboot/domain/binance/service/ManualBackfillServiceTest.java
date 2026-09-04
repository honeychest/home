package com.chs.springboot.domain.binance.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManualBackfillServiceTest {

    @Test
    void rejectsRawAggTradeBackfillAfterPhase4() {
        ManualBackfillService service = new ManualBackfillService(
                mock(JdbcTemplate.class),
                mock(BinanceKlineTempSyncService.class),
                mock(BinanceKline5mSyncService.class));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.startCollect("RAW_AGG_TRADE", "BTCUSDT", "SPOT", 1L, 2L, null, null));

        assertEquals("raw_agg_trade 백필은 중단되었습니다. KLINE_1M 백필을 사용하세요", error.getMessage());
    }

    @Test
    void startsKlineJobWithExplicitTimeRange() throws InterruptedException {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        BinanceKlineTempSyncService klineSyncService = mock(BinanceKlineTempSyncService.class);
        when(klineSyncService.rangeSync(eq("BTCUSDT"), eq("SPOT"), eq(60_000L), eq(120_000L)))
                .thenReturn(new BinanceKlineTempSyncService.RangeSyncResult(2, 1, false, false));

        ManualBackfillService service = new ManualBackfillService(
                jdbcTemplate, klineSyncService, mock(BinanceKline5mSyncService.class));
        String jobId = service.startCollect("KLINE_1M", "BTCUSDT", "SPOT", null, null, 60_000L, 120_000L);

        ManualBackfillService.JobStatus status = null;
        for (int i = 0; i < 100; i++) {
            status = service.getStatus(jobId);
            if (!"RUNNING".equals(status.status())) {
                break;
            }
            Thread.sleep(10L);
        }

        assertNotNull(status);
        assertEquals("DONE", status.status());
        assertEquals(2, status.inserted());
        verify(klineSyncService).rangeSync("BTCUSDT", "SPOT", 60_000L, 120_000L);
    }

    @Test
    void recordsErrorWhenKlineRangeStartsWithEmptyResponse() throws InterruptedException {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        BinanceKlineTempSyncService klineSyncService = mock(BinanceKlineTempSyncService.class);
        when(klineSyncService.rangeSync(eq("BTCUSDT"), eq("SPOT"), eq(60_000L), eq(120_000L)))
                .thenReturn(new BinanceKlineTempSyncService.RangeSyncResult(0, 1, false, true));

        ManualBackfillService service = new ManualBackfillService(
                jdbcTemplate, klineSyncService, mock(BinanceKline5mSyncService.class));
        String jobId = service.startCollect("KLINE_1M", "BTCUSDT", "SPOT", null, null, 60_000L, 120_000L);

        ManualBackfillService.JobStatus status = null;
        for (int i = 0; i < 100; i++) {
            status = service.getStatus(jobId);
            if (!"RUNNING".equals(status.status())) {
                break;
            }
            Thread.sleep(10L);
        }

        assertNotNull(status);
        assertEquals("ERROR", status.status());
        assertEquals("KLINE_1M Binance 응답이 비어 있어 백필하지 못했습니다", status.message());
        verify(klineSyncService).rangeSync("BTCUSDT", "SPOT", 60_000L, 120_000L);
    }

    @Test
    void startsKline5mBackfillJobWithExplicitTimeRange() throws InterruptedException {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        BinanceKline5mSyncService kline5mSyncService = mock(BinanceKline5mSyncService.class);
        when(kline5mSyncService.manualBackfillRange("BTCUSDT", "SPOT", 300_000L, 600_000L))
                .thenReturn(new BinanceKline5mSyncService.RefillResult(1, 1, 1, 1, 0, false, false));

        ManualBackfillService service = new ManualBackfillService(
                jdbcTemplate, mock(BinanceKlineTempSyncService.class), kline5mSyncService);
        String jobId = service.startCollect("KLINE_5M", "BTCUSDT", "SPOT", null, null, 300_000L, 600_000L);

        ManualBackfillService.JobStatus status = null;
        for (int i = 0; i < 100; i++) {
            status = service.getStatus(jobId);
            if (!"RUNNING".equals(status.status())) {
                break;
            }
            Thread.sleep(10L);
        }

        assertNotNull(status);
        assertEquals("DONE", status.status());
        assertEquals(1, status.inserted());
        verify(kline5mSyncService).manualBackfillRange("BTCUSDT", "SPOT", 300_000L, 600_000L);
    }

    @Test
    void recordsErrorWhenKline5mBackfillLeavesARemainingGap() throws InterruptedException {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        BinanceKline5mSyncService kline5mSyncService = mock(BinanceKline5mSyncService.class);
        when(kline5mSyncService.manualBackfillRange("BTCUSDT", "SPOT", 300_000L, 600_000L))
                .thenReturn(new BinanceKline5mSyncService.RefillResult(1, 0, 0, 0, 1, false, false));

        ManualBackfillService service = new ManualBackfillService(
                jdbcTemplate, mock(BinanceKlineTempSyncService.class), kline5mSyncService);
        String jobId = service.startCollect("KLINE_5M", "BTCUSDT", "SPOT", null, null, 300_000L, 600_000L);

        ManualBackfillService.JobStatus status = null;
        for (int i = 0; i < 100; i++) {
            status = service.getStatus(jobId);
            if (!"RUNNING".equals(status.status())) {
                break;
            }
            Thread.sleep(10L);
        }

        assertNotNull(status);
        assertEquals("ERROR", status.status());
        assertEquals("KLINE_5M 백필 후에도 1개 캔들이 남았습니다(expected=1 presentAfter=0)", status.message());
    }

    @Test
    void recordsErrorInsteadOfFalseSuccessWhenKline5mBackfillCollidesWithAnInFlightRefill() throws InterruptedException {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        BinanceKline5mSyncService kline5mSyncService = mock(BinanceKline5mSyncService.class);
        // in-flight 충돌 시 아무 일도 안 했는데 expected=0/remainingGap=0이라 "성공"처럼 보일 수 있다 —
        // skippedInFlight로 구분해 명시적 ERROR로 남겨야 한다.
        when(kline5mSyncService.manualBackfillRange("BTCUSDT", "SPOT", 300_000L, 600_000L))
                .thenReturn(new BinanceKline5mSyncService.RefillResult(0, 0, 0, 0, 0, false, true));

        ManualBackfillService service = new ManualBackfillService(
                jdbcTemplate, mock(BinanceKlineTempSyncService.class), kline5mSyncService);
        String jobId = service.startCollect("KLINE_5M", "BTCUSDT", "SPOT", null, null, 300_000L, 600_000L);

        ManualBackfillService.JobStatus status = null;
        for (int i = 0; i < 100; i++) {
            status = service.getStatus(jobId);
            if (!"RUNNING".equals(status.status())) {
                break;
            }
            Thread.sleep(10L);
        }

        assertNotNull(status);
        assertEquals("ERROR", status.status());
        assertEquals("같은 심볼/마켓의 KLINE_5M 리필이 이미 실행 중입니다", status.message());
    }
}
