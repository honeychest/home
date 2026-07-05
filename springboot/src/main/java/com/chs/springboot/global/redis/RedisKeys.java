// [AGENT] Redis 키 단일 지도 — 흩어진 문자열 리터럴을 한 곳에 카테고리별로 집결한다.
// Redis 는 폴더가 없고 ':' 접두어로 계층을 표현하는 게 관례. 아래 카테고리 = ':' 첫 마디.
// 물리 키 문자열은 기존과 동일(마이그레이션 0). 새 키/건드리는 키부터 이 상수를 참조한다.
//   cluster : 노드 조율(리더 선출)
//   config  : 앱 설정(★영속 — 지우면 기본값 리셋)
//   metric  : 메트릭 스냅샷(단명, TTL)
//   health  : 헬스 보드 클러스터 스냅샷(단명, TTL)
//   alert   : 알림 쿨다운/무음(단명)
//   adminip : 관리자 IP 승인(단명, TTL)
//   aggtrade: 체결 파이프라인(queue/checkpoint=★영속, lock/cache=단명)
// 기존 리터럴의 일괄 편입은 후속 커밋에서 진행(동작 불변 리팩터).
package com.chs.springboot.global.redis;

public final class RedisKeys {

    private RedisKeys() {
    }

    // ── cluster : 노드 조율 ──
    /** 리더 선출 리스(server:leader). ★재선출 빠름 */
    public static final String LEADER = "server:leader";

    // ── metric : 메트릭 스냅샷 ──
    /** 리더가 발행하는 전체 메트릭 스냅샷(120s TTL) */
    public static final String METRIC_SNAPSHOT = "monitor:snapshot";

    // ── health : 헬스 보드 클러스터 스냅샷 ──
    /** 리더가 발행하는 헬스 하트비트 원시상태 + 자원값 스냅샷(60s TTL). 비리더 보드가 읽어 노드 무관 표시 */
    public static final String HEALTH_SNAPSHOT = "health:snapshot";

    // ── aggtrade : 체결 파이프라인 ──
    /** 체결 인입 큐(★인플라이트 데이터) */
    public static final String AGGTRADE_QUEUE = "aggtrade:queue";
}
