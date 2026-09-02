package com.chs.springboot.global.admin.test.binance;

import com.chs.springboot.domain.binance.model.BinanceAnalysisResponse;
import com.chs.springboot.domain.binance.model.BinanceAnalysisStatus;
import com.chs.springboot.domain.binance.model.BinanceKline;
import com.chs.springboot.domain.binance.model.BinanceKlineInterval;
import com.chs.springboot.domain.binance.model.IntervalMarketSnapshot;
import com.chs.springboot.domain.binance.model.MarketDataStatus;
import com.chs.springboot.domain.binance.model.MultiTimeframeMarketSnapshot;
import com.chs.springboot.domain.binance.service.BinanceAutoTradeAnalysisService;
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
import java.util.List;

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

    @Mock
    BinanceAutoTradeAnalysisService analysisService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        BinanceAutoTradeDebugController controller = new BinanceAutoTradeDebugController(liveMarketDataService, analysisService);
        mockMvc = standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    @DisplayName("리더가 아니면 200 + status=NOT_LEADER (503 아님 — 전역 과부하 토스트 회피)")
    void getSnapshot_notLeader() throws Exception {
        when(liveMarketDataService.buildSnapshot()).thenReturn(snapshot(false, MarketDataStatus.ERROR));

        mockMvc.perform(get("/api/admin/test/binance/debug/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_LEADER"))
                .andExpect(jsonPath("$.snapshot.symbol").value("BTCUSDT"));
    }

    @Test
    @DisplayName("리더인데 캔들이 아직 없으면 200 + status=BACKFILLING")
    void getSnapshot_backfilling() throws Exception {
        when(liveMarketDataService.buildSnapshot()).thenReturn(snapshot(true, MarketDataStatus.BACKFILLING));

        mockMvc.perform(get("/api/admin/test/binance/debug/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BACKFILLING"));
    }

    @Test
    @DisplayName("캔들은 있는데 신선도 실패면 200 + status=STALE")
    void getSnapshot_stale() throws Exception {
        when(liveMarketDataService.buildSnapshot()).thenReturn(snapshot(true, MarketDataStatus.STALE));

        mockMvc.perform(get("/api/admin/test/binance/debug/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STALE"));
    }

    @Test
    @DisplayName("리더 + 캔들 있음 + 신선함 → 200 + status=READY + 스냅샷 필드")
    void getSnapshot_ready() throws Exception {
        when(liveMarketDataService.buildSnapshot()).thenReturn(snapshot(true, MarketDataStatus.READY));

        mockMvc.perform(get("/api/admin/test/binance/debug/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.snapshot.symbol").value("BTCUSDT"))
                .andExpect(jsonPath("$.snapshot.snapshots[0].candleCount").value(1))
                .andExpect(jsonPath("$.snapshot.snapshots[0].currentPrice").value(77000.5))
                .andExpect(jsonPath("$.snapshot.intervalStatuses[0].status").value("READY"));
    }

    @Test
    void getAnalysisAndAskExposeAnalysisStatuses() throws Exception {
        BinanceAnalysisResponse response = new BinanceAnalysisResponse(
                BinanceAnalysisStatus.READY, null, "답변", 1700000000000L,
                1700000001000L, 500L, 1700000001000L, "완료");
        when(analysisService.getLatestAnalysis()).thenReturn(response);
        when(analysisService.ask("현재가?", List.of())).thenReturn(response);

        mockMvc.perform(get("/api/admin/test/binance/debug/analysis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.asOfMs").value(1700000000000L));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/api/admin/test/binance/debug/analysis/ask")
                        .contentType("application/json")
                        .content("{\"question\":\"현재가?\",\"recentTurns\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("답변"));
    }

    private static MultiTimeframeMarketSnapshot snapshot(boolean leader, MarketDataStatus status) {
        List<IntervalMarketSnapshot> intervals = List.of(BinanceKlineInterval.values()).stream()
                .map(interval -> new IntervalMarketSnapshot(interval, status,
                        List.of(candle(interval)), null, 1700000000000L, "",
                        new BigDecimal("77000.5"), new BigDecimal("78000"), new BigDecimal("76500"),
                        new BigDecimal("-0.32"), null))
                .toList();
        return new MultiTimeframeMarketSnapshot("BTCUSDT", "FUTURES", 1700000000000L,
                leader, 1L, intervals, false);
    }

    private static BinanceKline candle(BinanceKlineInterval interval) {
        BigDecimal price = new BigDecimal("77000.5");
        return new BinanceKline(0L, price, price, price, price, BigDecimal.ONE,
                interval.intervalMs() - 1, BigDecimal.ONE, 1L, BigDecimal.ONE, BigDecimal.ONE);
    }
}
