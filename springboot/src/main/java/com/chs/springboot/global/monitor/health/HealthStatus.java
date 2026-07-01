// [AGENT] 헬스 체크 상태 — 보드 집계용 공통 상태값
package com.chs.springboot.global.monitor.health;

public enum HealthStatus {
    /** 정상 */
    UP,
    /** 경고(느려짐/지연) */
    DEGRADED,
    /** 다운(끊김/실패) */
    DOWN,
    /** 아직 계측 미구현 */
    UNKNOWN
}
