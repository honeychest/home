// [AGENT] Admin 테스트 — 자동매매 PoC 실시간 캔들 버퍼 상태 디버그 API
// (/api/admin/test/binance/debug/snapshot). ADMIN_ACCESS 권한 필요(SecurityConfig /api/admin/** 규칙).
// 서비스 조회만 하고 규칙은 LiveMarketDataService가 그대로 소유(README.md "디버그 API는
// 기존 서비스 조회·요약만" 원칙).
package com.chs.springboot.global.admin.test.binance;

import com.chs.springboot.domain.binance.model.BinanceAnalysisAskRequest;
import com.chs.springboot.domain.binance.model.BinanceAnalysisResponse;
import com.chs.springboot.domain.binance.service.BinanceAutoTradeAnalysisService;
import com.chs.springboot.domain.binance.service.LiveMarketDataService;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/test/binance/debug")
public class BinanceAutoTradeDebugController {

    private final LiveMarketDataService liveMarketDataService;
    private final BinanceAutoTradeAnalysisService analysisService;

    public BinanceAutoTradeDebugController(LiveMarketDataService liveMarketDataService,
                                           BinanceAutoTradeAnalysisService analysisService) {
        this.liveMarketDataService = liveMarketDataService;
        this.analysisService = analysisService;
    }

    @GetMapping("/snapshot")
    public AutoTradeSnapshotResponse getSnapshot() {
        return AutoTradeSnapshotResponse.from(liveMarketDataService.buildSnapshot());
    }

    @GetMapping("/analysis")
    public BinanceAnalysisResponse getAnalysis() {
        return analysisService.getLatestAnalysis();
    }

    @PostMapping("/analysis/refresh")
    public BinanceAnalysisResponse refreshAnalysis() {
        return analysisService.refreshAnalysis();
    }

    @PostMapping("/analysis/ask")
    public BinanceAnalysisResponse askAnalysis(@RequestBody(required = false) BinanceAnalysisAskRequest request) {
        if (request == null) {
            return analysisService.ask(null, null);
        }
        return analysisService.ask(request.question(), request.recentTurns());
    }
}
