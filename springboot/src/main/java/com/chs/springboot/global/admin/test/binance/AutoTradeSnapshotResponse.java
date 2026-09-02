// [AGENT] BinanceAutoTradeDebugController 응답 봉투 — 항상 200과 상태 필드로 준비 상태를 구분한다.
// 503을 안 쓰는 이유: apiClient.js가 모든 503에 전역 "서버 과부하" 토스트를 띄우는데
// (frontend/src/api/apiClient.js), 여기 상태들은 과부하가 아니라 정상적인 "아직 준비 중" 상태라
// 항상 200으로 응답하고 status 필드로만 구분한다.
package com.chs.springboot.global.admin.test.binance;

import com.chs.springboot.domain.binance.model.MarketDataStatus;
import com.chs.springboot.domain.binance.model.MultiTimeframeMarketSnapshot;
import com.chs.springboot.domain.binance.model.MultiTimeframeSnapshotDto;

public record AutoTradeSnapshotResponse(String status, MultiTimeframeSnapshotDto snapshot) {

    public static AutoTradeSnapshotResponse from(MultiTimeframeMarketSnapshot snapshot) {
        return new AutoTradeSnapshotResponse(overallStatus(snapshot), MultiTimeframeSnapshotDto.from(snapshot));
    }

    private static String overallStatus(MultiTimeframeMarketSnapshot snapshot) {
        if (!snapshot.leader()) {
            return "NOT_LEADER";
        }
        if (snapshot.intervals().stream().allMatch(interval -> interval.status() == MarketDataStatus.READY)) {
            return "READY";
        }
        if (snapshot.intervals().stream().allMatch(interval -> interval.status() == MarketDataStatus.STALE)) {
            return "STALE";
        }
        if (snapshot.intervals().stream().allMatch(interval -> interval.status() == MarketDataStatus.GAP)) {
            return "GAP";
        }
        if (snapshot.intervals().stream().allMatch(interval -> interval.status() == MarketDataStatus.ERROR)) {
            return "ERROR";
        }
        if (snapshot.intervals().stream().anyMatch(interval ->
                interval.status() == MarketDataStatus.STALE
                        || interval.status() == MarketDataStatus.GAP
                        || interval.status() == MarketDataStatus.ERROR)) {
            return "PARTIAL";
        }
        return "BACKFILLING";
    }
}
