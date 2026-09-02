// [AGENT] Admin 테스트 — 자동매매 PoC 실시간 캔들 버퍼 상태 디버그 API
// (/api/admin/test/binance/debug/snapshot). ADMIN_ACCESS 권한 필요(SecurityConfig /api/admin/** 규칙).
// 서비스 조회만 하고 규칙은 LiveMarketDataService가 그대로 소유(README.md "디버그 API는
// 기존 서비스 조회·요약만" 원칙).
package com.chs.springboot.global.admin.test.binance;

import com.chs.springboot.domain.binance.model.MarketSnapshotDto;
import com.chs.springboot.domain.binance.service.LiveMarketDataService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/test/binance/debug")
public class BinanceAutoTradeDebugController {

    private final LiveMarketDataService liveMarketDataService;

    public BinanceAutoTradeDebugController(LiveMarketDataService liveMarketDataService) {
        this.liveMarketDataService = liveMarketDataService;
    }

    @GetMapping("/snapshot")
    public AutoTradeSnapshotResponse getSnapshot() {
        if (!liveMarketDataService.isLeader()) {
            return AutoTradeSnapshotResponse.notReady("NOT_LEADER");
        }
        MarketSnapshotDto dto = liveMarketDataService.buildSnapshotDto();
        if (dto.candleCount() == 0) {
            return AutoTradeSnapshotResponse.notReady("BACKFILLING");
        }
        if (liveMarketDataService.isStale()) {
            return AutoTradeSnapshotResponse.notReady("STALE");
        }
        return AutoTradeSnapshotResponse.ready(dto);
    }
}
