// [AGENT] BinanceAutoTradeDebugController 응답 봉투 — status로 상태를 구분(READY/NOT_LEADER/BACKFILLING/STALE).
// 503을 안 쓰는 이유: apiClient.js가 모든 503에 전역 "서버 과부하" 토스트를 띄우는데
// (frontend/src/api/apiClient.js), 여기 상태들은 과부하가 아니라 정상적인 "아직 준비 중" 상태라
// 항상 200으로 응답하고 status 필드로만 구분한다.
package com.chs.springboot.global.admin.test.binance;

import com.chs.springboot.domain.binance.model.MarketSnapshotDto;

public record AutoTradeSnapshotResponse(String status, MarketSnapshotDto snapshot) {

    public static AutoTradeSnapshotResponse ready(MarketSnapshotDto dto) {
        return new AutoTradeSnapshotResponse("READY", dto);
    }

    public static AutoTradeSnapshotResponse notReady(String status) {
        return new AutoTradeSnapshotResponse(status, null);
    }
}
