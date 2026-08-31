package com.chs.springboot.domain.binance.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataGapAdminServiceTest {

    @Test
    void routesKlineGapRequestsWithoutChangingLegacyBranches() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        BinanceKlineGapService klineGapService = mock(BinanceKlineGapService.class);
        List<Map<String, Object>> expected = List.of(Map.of("status", "GAP"));
        when(klineGapService.findGaps(2, null, null)).thenReturn(expected);

        DataGapAdminService service = new DataGapAdminService(jdbcTemplate, klineGapService);

        assertEquals(expected, service.checkGap("KLINE_1M", 2, null, null));
        verify(klineGapService).findGaps(2, null, null);
    }
}
