// [AGENT] 헬스 체크 상태 전이 이벤트 — recorder가 전이 순간에만 발행, HealthAlertNotifier가 수신해 알림.
// recorder가 TelegramProvider를 직접 부르면 순환참조(TelegramProvider→recorder)라 이벤트로 분리한다.
// recovery=false: 실패 시작/악화(status=DOWN|DEGRADED) · recovery=true: 복구(status=닫힌 이벤트의 직전 상태)
package com.chs.springboot.global.monitor.health;

public record HealthCheckTransitionEvent(String checkKey, HealthStatus status, String cause, boolean recovery) {
}
