// [AGENT] 보드 표시용 체크 1건 뷰 DTO (팝오버 강화: 설명·판정근거·최근 실패 이력 포함)
package com.chs.springboot.global.monitor.health;

import java.util.List;

public record HealthCheckView(
        String key,
        String label,
        String description,  // 무엇을 점검하는지 한 줄 설명
        String layer,        // HealthLayer.label()
        String layerCode,    // HealthLayer.name() (그룹 정렬용)
        String priority,     // 치명/중요/여유
        HealthStatus status,
        String detail,       // 현재 상태 부가설명 (예: "12초 전 수신", "미구현")
        String thresholdText, // 판정 기준 (예: "경고 ≥10초 · 다운 ≥30초"), 없으면 null
        String lastFailedAt,  // 마지막 실패 시각 ISO, 없으면 null
        String lastCause,     // 마지막 실패 원인, 없으면 null
        boolean recentlyRecovered, // 현재 정상(UP)이나 최근 창(recent-window-hours) 내 복구된 장애가 있음 — 흔적 표시용
        String recoveredAt,   // 그 복구 시각(최신), recentlyRecovered=false 면 null
        List<Failure> recentFailures // 최근 실패 이력(최신순)
) {
    public record Failure(
            String at,
            HealthEventStatus status,
            String cause,
            String resolvedAt  // 복구 시각, 미복구면 null
    ) {
    }
}
