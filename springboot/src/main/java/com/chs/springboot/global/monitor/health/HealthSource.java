// [AGENT] 체크별 상태 소스 — 보드 표시 시 상태를 어디서 읽는지 카탈로그 항목이 스스로 선언한다.
// HealthCheckService.getChecks() 는 이 선언으로만 분기한다(키 문자열 가드 금지).
// HEARTBEAT 선언은 HealthHeartbeatConfig 등록과 양방향 일치해야 하며 기동 시 검증된다.
package com.chs.springboot.global.monitor.health;

public enum HealthSource {
    FEED,          // FeedHealthRegistry 신선도 스냅샷
    HEARTBEAT,     // HealthHeartbeat 경과 판정 (HealthHeartbeatConfig 임계 등록 필수)
    RESOURCE_PCT,  // MetricCollectorService 퍼센트 스냅샷 (cpu/ram/disk)
    RAWTABLE,      // raw_agg_trade 물리 크기 절대값 임계
    WSCONN,        // WS 세션 수 절대값 임계
    INFRA,         // InfraHealthProbe 능동 프로브 (UP 아니면 DOWN)
    EVENT          // 능동 평가기·호출지점 push 가 적립한 open 이벤트 유무
}
