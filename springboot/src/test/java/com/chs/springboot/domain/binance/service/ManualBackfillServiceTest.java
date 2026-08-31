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
                mock(BinanceKlineTempSyncService.class));

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

        ManualBackfillService service = new ManualBackfillService(jdbcTemplate, klineSyncService);
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

        ManualBackfillService service = new ManualBackfillService(jdbcTemplate, klineSyncService);
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
}
