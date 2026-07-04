# springboot(lab) — CONTEXT

> 코드·그래프(GitNexus)가 줄 수 없는 것만 담는다 — 도메인 용어, 불변 규칙, 설계 '이유'.
> 호출관계·실행 흐름은 GitNexus가 항상 최신으로 가진다.

## 용어 (시스템 헬스체크)

- **점검 항목(check)**: 헬스보드 한 줄. `HealthCheckCatalog`(34개)가 단일 목록이자 항목별 지식의
  집: 소스·하트비트 임계(stale/down 초)·판정 기준 문구(thresholdText)를 항목이 자기 줄에서 제공. (2026-07)
  키는 기록자(writer)당 하나 — 기록기는 키당 열린 이벤트 1개 모델이라 키를 공유하면 한쪽의 정상
  기록이 다른 쪽 장애를 닫아버린다(구 ext-security-scan 을 ext-virustotal/ext-safebrowsing 으로 분리, 2026-07).
- **상태(HealthStatus)**: UP / DEGRADED / DOWN / UNKNOWN. 전 시스템 단일 낱말 —
  피드 전용 낱말(FeedStatus의 STALE)은 2026-07 에 DEGRADED 로 통합·폐기.
- **상태 사다리(StatusLadder)**: "측정값이 경고선 이상이면 DEGRADED, 위험선 이상이면 DOWN"
  판정의 단일 구현 + 임계값 상수 집결처 + 임계 문구 생성. 표시와 기록이 같은 사다리를 읽는다.
  rawtable/ws 는 판정+판정근거 문구를 Judged 묶음으로 사다리가 제공 — 표시(HealthCheckService)와
  기록(ResourceHealthEvaluator)이 같은 구현을 호출한다. (2026-07)
  (하트비트 13종의 stale/down 초는 카탈로그 각 항목이 자기 줄에 선언 — HealthHeartbeatConfig 는
  카탈로그를 읽어 등록만 하므로 선언↔등록 불일치가 구조적으로 불가능. 2026-07)
- **이벤트 수명 상태(HealthEventStatus)**: 실패 이벤트의 수명 축 — 열림(DOWN/DEGRADED)에서
  닫힘(RESOLVED)으로 흐른다. 판정(HealthStatus)과 다른 낱말이며, 문자열↔타입 변환은 JPA 경계
  한 곳(@Enumerated, 저장 문자열 동일)에서만 일어난다. (2026-07)
- **기록기(HealthCheckRecorder)**: 계측의 단일 입구 `record(점검키, 상태, 원인)`.
  심각도는 상태에서 파생(DOWN=CRITICAL, DEGRADED=WARN), UP=복구, UNKNOWN=무동작.
  호출자가 심각도를 고르는 인터페이스(markFail)는 비공개화됨.
- **평가기(evaluator)**: 주기적으로 측정해 기록기에 넘기는 스케줄러들. 판정하지 않고
  측정과 배선만 담당한다(판정은 사다리, 기록 정책은 기록기).

## 설계 결정과 이유

- 헬스보드 표시: 메모리 값(피드·하트비트·자원)은 실시간으로 읽고, 인프라(infra-*)는
  InfraHealthEvaluator(20초, 리더)가 적립한 이벤트를 읽는다 — 보드 요청 경로에서 실접속
  프로브를 제거해 대상 장애 시 접속 시간초과로 보드가 느려지는 것을 막기 위함. (2026-07)
  전면 이벤트 기반 통일은 정상 행의 실시간 수치·UNKNOWN 표시가 사라져 채택하지 않음.
- 피드의 "수신 기록 없음"은 DOWN(피드는 항상 흘러야 정상), 하트비트의 "관측 없음"은
  UNKNOWN(비리더 인스턴스 오탐 방지) — 같은 '없음'이라도 의도가 다르다.
