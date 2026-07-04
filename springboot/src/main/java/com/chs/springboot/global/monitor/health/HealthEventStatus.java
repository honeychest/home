// [AGENT] 이벤트 수명 상태 — health_check_event.status 컬럼의 타입. HealthStatus(판정)와 다른 축:
// 실패 이벤트가 열림(DOWN/DEGRADED)에서 닫힘(RESOLVED)으로 흐른다.
// 문자열↔타입 변환은 JPA 경계(@Enumerated) 한 곳 — 저장 문자열은 기존과 동일해 마이그레이션 불필요.
package com.chs.springboot.global.monitor.health;

public enum HealthEventStatus {
    DOWN, DEGRADED, RESOLVED;

    /** 실패 판정(DOWN/DEGRADED)을 이벤트 상태로 변환. 그 외 판정은 실패 이벤트가 될 수 없다(fail-fast). */
    static HealthEventStatus fromFailure(HealthStatus status) {
        return switch (status) {
            case DOWN -> DOWN;
            case DEGRADED -> DEGRADED;
            default -> throw new IllegalArgumentException("실패 이벤트 상태가 아님: " + status);
        };
    }

    /** 보드 표시용 판정 상태로 환원. RESOLVED 는 open 이벤트에서 불가능한 상태 — 방어적으로 UNKNOWN. */
    HealthStatus asHealthStatus() {
        return switch (this) {
            case DOWN -> HealthStatus.DOWN;
            case DEGRADED -> HealthStatus.DEGRADED;
            case RESOLVED -> HealthStatus.UNKNOWN;
        };
    }
}
