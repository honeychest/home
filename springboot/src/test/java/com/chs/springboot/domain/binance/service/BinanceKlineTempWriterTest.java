package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.BinanceKline;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BinanceKlineTempWriterTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    @Test
    void countsOnlyActuallyInsertedRowsAndUsesInsertIgnore() {
        doReturn(new int[]{1, 0, 1}).when(jdbcTemplate).batchUpdate(anyString(), anyList());

        int inserted = new BinanceKlineTempWriter(jdbcTemplate)
                .insertIgnore("BTCUSDT", "SPOT", List.of(kline(0L), kline(60_000L), kline(120_000L)));

        assertEquals(2, inserted);
        verify(jdbcTemplate).batchUpdate(
                org.mockito.ArgumentMatchers.contains("INSERT IGNORE INTO agg_trade_1m_temp"), anyList());
    }

    @Test
    void duplicateRerunReportsZeroWithoutChangingExistingRows() {
        doReturn(new int[]{1}).doReturn(new int[]{0})
                .when(jdbcTemplate).batchUpdate(anyString(), anyList());
        BinanceKlineTempWriter writer = new BinanceKlineTempWriter(jdbcTemplate);

        assertEquals(1, writer.insertIgnore("BTCUSDT", "SPOT", List.of(kline(0L))));
        assertEquals(0, writer.insertIgnore("BTCUSDT", "SPOT", List.of(kline(0L))));
    }

    @Test
    void concurrentRerunsCountOneInsertAndOneDuplicate() throws Exception {
        AtomicBoolean firstInsert = new AtomicBoolean(true);
        org.mockito.Mockito.doAnswer(invocation -> firstInsert.getAndSet(false) ? new int[]{1} : new int[]{0})
                .when(jdbcTemplate).batchUpdate(anyString(), anyList());
        BinanceKlineTempWriter writer = new BinanceKlineTempWriter(jdbcTemplate);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> writer.insertIgnore("BTCUSDT", "SPOT", List.of(kline(0L))));
            Future<Integer> second = executor.submit(() -> writer.insertIgnore("BTCUSDT", "SPOT", List.of(kline(0L))));
            assertEquals(1, first.get() + second.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private BinanceKline kline(long openTimeMs) {
        return new BinanceKline(openTimeMs, new BigDecimal("10"), new BigDecimal("12"),
                new BigDecimal("9"), new BigDecimal("11"), new BigDecimal("2"),
                openTimeMs + 59_999L, new BigDecimal("20"), 3L,
                new BigDecimal("1"), new BigDecimal("10"));
    }
}
