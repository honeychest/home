package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.BinanceKline;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BinanceKline5mWriterTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    @Test
    void countsOnlyActuallyInsertedRowsAndUsesInsertIgnoreOnBinanceKline5mTable() {
        doReturn(new int[]{1, 0, 1}).when(jdbcTemplate).batchUpdate(anyString(), anyList());

        int inserted = new BinanceKline5mWriter(jdbcTemplate)
                .insertIgnore("BTCUSDT", "SPOT", List.of(kline(0L), kline(300_000L), kline(600_000L)));

        assertEquals(2, inserted);
        verify(jdbcTemplate).batchUpdate(
                org.mockito.ArgumentMatchers.contains("INSERT IGNORE INTO binance_kline_5m"), anyList());
    }

    @Test
    void duplicateRerunReportsZeroWithoutChangingExistingRows() {
        doReturn(new int[]{1}).doReturn(new int[]{0})
                .when(jdbcTemplate).batchUpdate(anyString(), anyList());
        BinanceKline5mWriter writer = new BinanceKline5mWriter(jdbcTemplate);

        assertEquals(1, writer.insertIgnore("BTCUSDT", "SPOT", List.of(kline(0L))));
        assertEquals(0, writer.insertIgnore("BTCUSDT", "SPOT", List.of(kline(0L))));
    }

    private BinanceKline kline(long openTimeMs) {
        return new BinanceKline(openTimeMs, new BigDecimal("10"), new BigDecimal("12"),
                new BigDecimal("9"), new BigDecimal("11"), new BigDecimal("2"),
                openTimeMs + 299_999L, new BigDecimal("20"), 3L,
                new BigDecimal("1"), new BigDecimal("10"));
    }
}
