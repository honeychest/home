// [AGENT] 자동매매 PoC — 현재 데이터 상태 확인용 관리자 API. /api/admin/** 는 SecurityConfig에서
// ADMIN_ACCESS 권한 요구.
// 엔드포인트: GET /api/admin/binance/auto-trade/snapshot
package com.chs.springboot.domain.binance.controller;

import com.chs.springboot.domain.binance.model.MarketSnapshotDto;
import com.chs.springboot.domain.binance.service.LiveMarketDataService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/binance/auto-trade")
public class AutoTradeAdminController {

    private final LiveMarketDataService liveMarketDataService;

    public AutoTradeAdminController(LiveMarketDataService liveMarketDataService) {
        this.liveMarketDataService = liveMarketDataService;
    }

    @GetMapping("/snapshot")
    public ResponseEntity<?> getSnapshot() {
        if (!liveMarketDataService.isLeader()) {
            return unavailable("NOT_LEADER", "이 인스턴스는 리더 노드가 아닙니다 — 리더 노드에서 조회하세요");
        }
        MarketSnapshotDto dto = liveMarketDataService.buildSnapshotDto();
        if (dto.candleCount() == 0) {
            return unavailable("BACKFILLING", "초기 적재 중입니다 — 잠시 후 다시 조회하세요");
        }
        if (liveMarketDataService.isStale()) {
            return unavailable("STALE", "최근 데이터 갱신이 지연되고 있습니다");
        }
        return ResponseEntity.ok(dto);
    }

    private ResponseEntity<Map<String, Object>> unavailable(String code, String message) {
        return ResponseEntity.status(503).body(Map.of("code", code, "message", message));
    }
}
