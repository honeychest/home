package com.chs.springboot.global.admin.test.binance;

import com.chs.springboot.domain.binance.model.MarketSnapshotDto;
import com.chs.springboot.domain.binance.service.LiveMarketDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * BinanceAutoTradeDebugController — MockMvc standalone (전체 웹 슬라이스 없이 HTTP 계약만 검증)
 */
@ExtendWith(MockitoExtension.class)
class BinanceAutoTradeDebugControllerWebMvcTest {

    @Mock
    LiveMarketDataService liveMarketDataService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        BinanceAutoTradeDebugController controller = new BinanceAutoTradeDebugController(liveMarketDataService);
        mockMvc = standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    @DisplayName("리더가 아니면 200 + status=NOT_LEADER (503 아님 — 전역 과부하 토스트 회피)")
    void getSnapshot_notLeader() throws Exception {
        when(liveMarketDataService.isLeader()).thenReturn(false);

        mockMvc.perform(get("/api/admin/test/binance/debug/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_LEADER"))
                .andExpect(jsonPath("$.snapshot").doesNotExist());
    }

    @Test
    @DisplayName("리더인데 캔들이 아직 없으면 200 + status=BACKFILLING")
    void getSnapshot_backfilling() throws Exception {
        when(liveMarketDataService.isLeader()).thenReturn(true);
        when(liveMarketDataService.buildSnapshotDto()).thenReturn(
                new MarketSnapshotDto("BTCUSDT", "FUTURES", "1m", 0, 0L, null, null, null, null,
                        null, null, null, null, null, null));

        mockMvc.perform(get("/api/admin/test/binance/debug/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BACKFILLING"));
    }

    @Test
    @DisplayName("캔들은 있는데 신선도 실패면 200 + status=STALE")
    void getSnapshot_stale() throws Exception {
        when(liveMarketDataService.isLeader()).thenReturn(true);
        when(liveMarketDataService.buildSnapshotDto()).thenReturn(
                new MarketSnapshotDto("BTCUSDT", "FUTURES", "1m", 480, 1L,
                        new BigDecimal("100"), new BigDecimal("110"), new BigDecimal("90"), new BigDecimal("1.5"),
                        new BigDecimal("55.00"), new BigDecimal("1.2"), new BigDecimal("1.0"), new BigDecimal("0.2"),
                        new BigDecimal("95.00"), true));
        when(liveMarketDataService.isStale()).thenReturn(true);

        mockMvc.perform(get("/api/admin/test/binance/debug/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STALE"));
    }

    @Test
    @DisplayName("리더 + 캔들 있음 + 신선함 → 200 + status=READY + 스냅샷 필드")
    void getSnapshot_ready() throws Exception {
        when(liveMarketDataService.isLeader()).thenReturn(true);
        when(liveMarketDataService.buildSnapshotDto()).thenReturn(
                new MarketSnapshotDto("BTCUSDT", "FUTURES", "1m", 480, 1700000000000L,
                        new BigDecimal("77000.5"), new BigDecimal("78000"), new BigDecimal("76500"), new BigDecimal("-0.32"),
                        new BigDecimal("62.50"), new BigDecimal("15.2"), new BigDecimal("10.1"), new BigDecimal("5.1"),
                        new BigDecimal("76800.00"), true));
        when(liveMarketDataService.isStale()).thenReturn(false);

        mockMvc.perform(get("/api/admin/test/binance/debug/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.snapshot.symbol").value("BTCUSDT"))
                .andExpect(jsonPath("$.snapshot.candleCount").value(480))
                .andExpect(jsonPath("$.snapshot.currentPrice").value(77000.5))
                .andExpect(jsonPath("$.snapshot.rsi14").value(62.50))
                .andExpect(jsonPath("$.snapshot.macdHistogram").value(5.1))
                .andExpect(jsonPath("$.snapshot.supertrendValue").value(76800.00))
                .andExpect(jsonPath("$.snapshot.supertrendUptrend").value(true));
    }
}
