# springboot(lab) — CONTEXT

> 코드·그래프(GitNexus)가 줄 수 없는 것만 담는다 — 도메인 용어, 불변 규칙, 설계 '이유'.
> 호출관계·실행 흐름은 GitNexus가 항상 최신으로 가진다.

## 용어 (시스템 헬스체크)

- **점검 항목(check)**: 헬스보드 한 줄. `HealthCheckCatalog`(33개)가 단일 목록.
- **상태(HealthStatus)**: UP / DEGRADED / DOWN / UNKNOWN. 전 시스템 단일 낱말 —
  피드 전용 낱말(FeedStatus의 STALE)은 2026-07 에 DEGRADED 로 통합·폐기.
- **상태 사다리(StatusLadder)**: "측정값이 경고선 이상이면 DEGRADED, 위험선 이상이면 DOWN"
  판정의 단일 구현 + 임계값 상수 집결처 + 임계 문구 생성. 표시와 기록이 같은 사다리를 읽는다.
  (하트비트 13종의 stale/down 초는 예외로 HealthHeartbeatConfig 가 단일 소스)
- **기록기(HealthCheckRecorder)**: 계측의 단일 입구 `record(점검키, 상태, 원인)`.
  심각도는 상태에서 파생(DOWN=CRITICAL, DEGRADED=WARN), UP=복구, UNKNOWN=무동작.
  호출자가 심각도를 고르는 인터페이스(markFail)는 비공개화됨.
- **평가기(evaluator)**: 주기적으로 측정해 기록기에 넘기는 스케줄러들. 판정하지 않고
  측정과 배선만 담당한다(판정은 사다리, 기록 정책은 기록기).

## 설계 결정과 이유

- 표시 경로(헬스보드 실시간 재계산)와 기록 경로(평가기→이벤트 적립)는 의도적으로 분리 유지 —
  보드는 실시간성을 우선한다. (2026-07 아키텍처 리뷰의 후보 2는 채택하지 않음)
- 피드의 "수신 기록 없음"은 DOWN(피드는 항상 흘러야 정상), 하트비트의 "관측 없음"은
  UNKNOWN(비리더 인스턴스 오탐 방지) — 같은 '없음'이라도 의도가 다르다.
