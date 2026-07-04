// [AGENT] /events 응답용 이벤트 1건 뷰 DTO — /checks(HealthCheckView)와 같은 타입드 직렬화 방식
package com.chs.springboot.global.monitor.health;

public record HealthEventView(
        String checkKey,
        HealthEventStatus status,
        String severity,      // WARN / CRITICAL
        String cause,
        String firstFailedAt, // yyyy-MM-dd HH:mm:ss, 없으면 null
        String lastFailedAt,
        String resolvedAt     // 복구 시각, 미복구면 null
) {
}
