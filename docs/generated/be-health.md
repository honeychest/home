# Health 도메인 (시스템 헬스 체크 보드 · 백엔드)

> wiki-refresh로 실제 소스를 읽고 검증함(2026-07-06). 페이지 간 관계는 `index.md` 참고.
> 소스 위치: `springboot/src/main/java/com/chs/springboot/global/monitor/health/` (27개 클래스). 설계 원본은 `docs/health-check-board.md`.

## 한 줄 요약
시스템의 인프라·피드·파이프라인·데이터·스케줄러·외부연동·리소스 7계층을 **25개 체크(하트비트 6개)로 상시 점검**하고, "전부 OK"를 한 화면에서 확인하는 운영자용 헬스 보드의 백엔드다. 정상은 저장하지 않고 **실패(FAIL) 전환·복구만** `health_check_event` 테이블에 적립하며, DOWN 발생/복구 시 텔레그램으로 알린다. API는 `/api/admin/health/**`.

## 이런 걸 물을 때 찾으면 된다 (검색 키워드)
- "헬스 체크 보드 / admin health / 시스템 상태 점검 / 전부 정상 확인"
- "하트비트 어떻게 등록해 / beat fail / watchdog / 새 체크 추가하는 법"
- "infra-mysql / infra-redis / infra-postgres / feed-binance-ticker / sched-leader-election / res-cpu 는 뭐야"
- "헬스 체크 상태 UP DEGRADED DOWN UNKNOWN 판정 기준 / 임계값 / StatusLadder"
- "장애 알림 텔레그램 / DOWN 알림 / DEGRADED 는 왜 알림 안 와"
- "health_check_event 테이블 / 실패 이력 저장 / 리테이션 30일"
- "리더 노드에서만 점검 / 비리더 대기 UNKNOWN / 클러스터 스냅샷"

## 핵심 개념·용어
- **체크(check)**: 점검 항목 1개. `HealthCheckCatalog` enum에 25개가 코드로 고정돼 있고, 각 항목이 자기 계층·우선순위·상태 소스·판정 임계를 한 줄로 선언한다.
- **checkKey**: 체크의 문자열 식별자(예: `infra-mysql`, `sched-leader-election`). `health_check_event.check_key`로 이력과 연결된다.
- **계층(HealthLayer)**: 보드 그룹 단위. S1 인프라 → S2 피드 → S3 파이프라인 → S4 데이터 무결성 → S5 리더/스케줄러 → S6 외부연동 → S7 리소스 (enum 이름은 `L1_INFRA`~`L7_RESOURCE`, 화면 라벨은 `S1`~`S7`).
- **우선순위(HealthPriority)**: `CRITICAL`(치명)·`HIGH`(중요)·`LOW`(여유). 치명=끊기면 수집·저장·차트가 즉시 망가짐.
- **상태(HealthStatus)**: `UP`(정상)·`DEGRADED`(경고/지연)·`DOWN`(다운/실패)·`UNKNOWN`(미관측/대기).
- **상태 소스(HealthSource)**: 각 체크의 상태를 "어디서 읽고 어떻게 판정하는지". 6종(FEED/HEARTBEAT/RESOURCE_PCT/WSCONN/INFRA/EVENT). 판정 로직과 임계 문구를 소스 스스로 소유한다.
- **하트비트(heartbeat)**: 주기 잡이 성공할 때 `beat`, 실패할 때 `fail`을 남기고, watchdog이 "마지막 성공 후 경과"로 UP/DEGRADED/DOWN을 판정하는 패턴.
- **이벤트 이력(health_check_event)**: 실패가 열리고(open, DOWN/DEGRADED) 복구되면 닫히는(RESOLVED) 이력 레코드. 정상 지속 시에는 아무것도 쓰지 않는다.
- **리더(leader)**: Redis 리더 선출로 뽑힌 단일 노드. 능동 점검(프로브/평가기)과 스냅샷 발행은 리더만 수행해 다중 노드 flapping을 막는다.

## 구조 / 흐름 (확인된 코드 기준)

파일 위치: `springboot/src/main/java/com/chs/springboot/global/monitor/health/`

### 큰 그림 — "상시 점검 → 이력 적립 → 보드는 읽기만"
```
 [백그라운드 평가기들(리더 전용)]        [기록]                    [표시]
 InfraHealthEvaluator(20s) ─┐
 FeedHealthEvaluator(5s)    ├─▶ HealthCheckRecorder.record ─▶ health_check_event
 DataIntegrityEvaluator(2m) │      (FAIL 전환·복구만 저장)         (open/RESOLVED 이력)
 HeartbeatWatchdog(10s)     ┘            │                              │
 각 잡의 beat/fail ─▶ HealthHeartbeat ───┘                              ▼
                                     (상태 전환 시) ──▶ HealthAlertNotifier ──▶ 텔레그램
                                                                        │
 GET /api/admin/health/checks ─▶ HealthCheckService.getChecks() ─▶ 25개 체크 live 판정 + 최근 실패 3건
```
핵심: **보드 요청 경로에는 실접속 프로브가 없다.** 화면은 이미 적립된 이벤트/하트비트/스냅샷을 읽어 판정만 하므로 빠르고, 정상 지속 시 DB write는 0이다.

### API — `HealthCheckController` (`@RequestMapping("/api/admin/health")`)
- `GET /checks` — 25개 체크 전체 + 상태 요약. 응답: `{ generatedAt, summary{ total, up, degraded, down, unknown, allOk }, checks[] }`. `allOk`는 DOWN·DEGRADED가 모두 0일 때 true.
- `GET /events` — 최근 실패 이력 100건(최신순, `HealthEventView`).
- 보호: `SecurityConfig`가 `/api/admin/**`를 `ADMIN_ACCESS` 권한으로 자동 보호(별도 어노테이션 없음).

### 집계 — `HealthCheckService.getChecks()`
- `HealthCheckCatalog.all()`(25개)을 순회하며 각 체크의 상태를 `c.source().judge(c, ports)` **한 줄로만** 판정한다(서비스는 소스별로 분기하지 않음 — 판정은 소스가 소유).
- `Ports`(요청마다 구성)로 협력자를 넘긴다: `FeedHealthRegistry` 스냅샷 · `HealthHeartbeat` · `MetricCollectorService` · `HealthCheckEventRepository` · 리더 발행 `ClusterView`.
- 각 체크에 최근 실패 3건(`findTop3ByCheckKeyOrderByLastFailedAtDesc`)을 붙이고, **현재 UP인데 최근 창(`recent-window-hours`, 기본 24h) 안에 복구된 장애**가 있으면 `recentlyRecovered=true`로 "최근이상" 흔적을 표시한다.
- 반환 DTO는 `HealthCheckView`(key/label/description/layer/layerCode/priority/status/detail/thresholdText/최근실패 등).

### 카탈로그 — `HealthCheckCatalog` (25개 enum, 마스터 체크리스트)
각 항목이 `(key, layer, priority, source, [feedId | 하트비트 임계], label, description)`를 한 줄로 선언. 정적 초기화 블록이 "FEED 소스↔feedId", "HEARTBEAT 소스↔임계 선언"의 일치를 **클래스 로딩 시점에 강제**(fail-fast).

| 계층 | checkKey (우선순위) | 상태 소스 |
|------|--------------------|-----------|
| **S1 인프라** | infra-mysql·infra-redis·infra-postgres (전부 치명) | INFRA |
| **S2 피드** | feed-binance-ticker(치명)·feed-binance-aggtrade(치명)·feed-upbit(중요) | FEED |
|  | feed-ws-reconnect(중요) | EVENT |
| **S4 데이터** | data-candle-gap(중요)·data-quality(중요) | EVENT |
| **S5 스케줄러** | sched-leader-election(치명 15/30)·sched-weather(중요 1500/2100)·sched-news(중요 720/1200)·sched-telegram-poll(중요 150/300)·sched-openinterest-poll(중요 150/300)·sched-analysis(중요 180/360) | HEARTBEAT |
| **S6 외부연동** | ext-telegram-send(중요)·ext-llm(중요)·ext-weather-api(여유)·ext-news-rss(여유)·ext-virustotal(여유)·ext-safebrowsing(여유) | EVENT |
| **S7 리소스** | res-cpu·res-ram·res-disk(중요) | RESOURCE_PCT |
|  | res-ws-connections(여유) | WSCONN |

> 제거된 체크(현재 `HealthCheckCatalog` 미등록): `PIPE_KAFKA_CONSUMER`(`pipe-kafka-consumer`), `PIPE_AGGTRADE_FLUSH`(`pipe-aggtrade-flush`), `RES_RAWTABLE_GROWTH`(`res-rawtable-growth`)는 Phase 4에서, `PIPE_ROLLUP_1S`(`pipe-rollup-1s`), `PIPE_ROLLUP_1M`(`pipe-rollup-1m`), `PIPE_ROLLUP_5M`(`pipe-rollup-5m`), `PIPE_EMPTY_CANDLE_FIX`(`pipe-empty-candle-fix`)는 Part A에서 제거했다.

> HEARTBEAT 항목 옆 `A/B`는 경고(stale)/다운 임계 초. 대상 주기의 약 2.5×/5× grace로 잡는다. 예: `sched-leader-election`은 5초 주기라 15초 경과=경고, 30초=다운. `agentRunner`(Codex runner) 체크는 lab(home) 기준이라 제외.

### 상태 소스 6종 — `HealthSource` enum (판정 + 임계 문구를 스스로 소유)
- **INFRA**: `InfraHealthEvaluator`가 적립한 open 이벤트 기반(UP 아니면 DOWN). 보드 경로에서 실접속 안 함. 문구 "20초 주기 능동 프로브 기록 기반".
- **FEED**: `FeedHealthRegistry` 신선도(마지막 수신 경과 초). 사다리 `FEED_SECONDS`(경고 10s·다운 30s). 리더가 관측하면 로컬값, 비리더는 리더 발행 스냅샷으로 재판정.
- **HEARTBEAT**: `HealthHeartbeat.evaluate(key)`로 "마지막 성공 경과" 판정. 항목이 선언한 stale/down 초를 임계로 사용. 비리더는 클러스터 스냅샷 원시상태로 동일 로직 재판정.
- **RESOURCE_PCT**: `MetricCollectorService`의 cpu/ram/disk %. 사다리 `RESOURCE_PCT`(경고 70%·다운 80%, `AlertService` CRITICAL 알림 임계와 **동일 상수**). ram/disk는 비리더 폴백을 리더값에서 읽음(cpu는 양 노드 관측).
- **WSCONN**: WS 세션 합계(4개 핸들러 합). 사다리 `WS_CONNS`(경고 300·다운 800).
- **EVENT**: 능동 평가기(L4)나 호출지점 push(L6·ws-reconnect)가 적립한 open 이벤트 유무. open 없으면 UP("알려진 실패 없음" 낙관). 임계 문구 없음(사용 시점 push).

판정은 항상 두 가지를 반환한다: `Judgement(status, detail)` — 상태 + 사람이 읽는 판정근거 문구(예: "12초 전 수신 (누적 34012)", "180초 동안 성공 없음", "대기 — 아직 실행 기록 없음").

### 하트비트 — `HealthHeartbeat` + `HeartbeatWatchdog` + `HealthHeartbeatConfig`
- 현재 `HealthCheckCatalog`에 등록된 HEARTBEAT는 6개이며, 비활성화된 raw 롤업의 하트비트는 등록하지 않는다.
- `HealthHeartbeat`: 잡이 `beat(key)`(성공, 진행 중 실패상태 해제)·`fail(key, cause)`(실패, 즉시 다운 신호)를 남기는 인메모리 레지스트리. `FeedHealthRegistry`의 일반화.
- 판정(`judge`): spec 미등록/미관측 = **UNKNOWN(대기)**, 마지막이 실패 = DOWN, 그 외 마지막 성공 경과가 downSeconds↑=DOWN · staleSeconds↑=DEGRADED · 그 외 UP. 로컬 evaluate와 비리더 클러스터 판정이 같은 static 로직을 쓴다.
- **핵심 설계**: 한 번도 beat 없으면 UNKNOWN → 리더 전용 잡이 비리더 인스턴스에서 "다운"으로 오탐 나지 않는다.
- `HeartbeatWatchdog`(`@Scheduled` fixedDelay 10초): 등록된 하트비트 전체를 평가해 `recorder.record`로 실패/복구 적립("조용히 멈춘" 잡을 여기서 포착). 리더면 추가로 `clusterSnapshot.publish()`(원시 하트비트 + 자원값 발행).
- `HealthHeartbeatConfig`: `HealthHeartbeat` 빈을 만들며 카탈로그의 HEARTBEAT 항목 임계를 **자동 등록** — 선언↔등록 불일치가 구조적으로 불가능.

### 능동 평가기들 (전부 리더 노드에서만 실행)
- `InfraHealthEvaluator`(20초): `InfraHealthProbe`로 mysql/redis/postgres 프로브 → `recorder.record`. 키별 격리(한 대상 실패가 나머지를 막지 않음). MySQL 프로브는 기존 커넥션 풀에서 커넥션을 빌려 `isValid` 확인(신규 풀 없음), Redis는 `PING`→`PONG`. **한계: MySQL 자체 다운은 이벤트 저장소가 MySQL이라 저장·알림 불가(로그만).**
- `FeedHealthEvaluator`(5초): `FeedHealthRegistry` 스냅샷 → checkKey 매핑 → 적립. 피드 WS는 리더만 소유하므로 리더만 평가(flapping 방지).
- `DataIntegrityEvaluator`(2분): 대표 심볼 `BTCUSDT/FUTURES` 최근 60분 `agg_trade_1m`을 능동 쿼리. **gap**=누락봉 수(사다리 `CANDLE_GAP` 1/3봉) → `data-candle-gap`, **quality**=flat 캔들 비율(`FLAT_PCT` 10/30%) → `data-quality`. 최근 2분은 롤업 지연 여유로 제외.
- 리소스(res-cpu/ram/disk/ws)는 별도 평가기가 아니라 `MetricCollectorService`가 이미 수집한 스냅샷을 보드 요청 시점에 임계 판정한다(스냅샷 재사용 패턴).

### 실패/복구 기록 — `HealthCheckRecorder` (모든 계측의 단일 입구)
- `record(checkKey, status, cause)`: 상태에서 심각도 파생 — DOWN→CRITICAL·markFail, DEGRADED→WARN·markFail, UP→markOk(진행 중 이벤트 닫기), **UNKNOWN→무동작(오탐 방지)**.
- `markFail`: 진행 중(open) 이벤트가 있으면 갱신, 없으면 새로 open. **상태 전이(신규 open 또는 DOWN↔DEGRADED 변화) 순간에만** `HealthCheckTransitionEvent` 발행 → 반복 실패 도배 방지.
- `markOk`: open 이벤트에 `resolvedAt`을 찍어 닫고, 직전 상태를 담은 복구 이벤트 발행.
- 환경 구분 `sourceEnv`(prod/local 정규화)로 이력 분리.

### 알림 — `HealthAlertNotifier`
- `@Async @EventListener`로 `HealthCheckTransitionEvent` 수신. 텔레그램 HTTP를 발행 스레드에서 분리(record/markOk 지연 방지).
- **정책**: DOWN 시작(🔴)·DOWN이던 장애의 복구(🟢)만 발송. **DEGRADED(경고)는 보드에만 표시, 텔레그램 미발송.** `ext-telegram-send`는 제외(텔레그램 장애를 텔레그램으로 알릴 수 없음 + 자기루프 방지).
- `monitor.health.alert-enabled`(기본 true) — 로컬 프로필은 false로 꺼 운영 채팅 오염 방지(이력 기록은 유지).

### 저장소 — `health_check_event` 테이블 (마이그레이션 V9)
- 엔티티 `HealthCheckEvent`: `check_key` · `status`(`HealthEventStatus`: DOWN/DEGRADED/**RESOLVED**) · `severity`(WARN/CRITICAL) · `cause`(@Lob) · `first_failed_at` · `last_failed_at` · `resolved_at` · `source_env` · created/updated. 인덱스 (check_key, last_failed_at) / (status, last_failed_at).
- `HealthEventStatus`는 **이벤트 수명 축**(열림 DOWN/DEGRADED → 닫힘 RESOLVED)으로 `HealthStatus`(판정 축)와 구분된다.
- 리테이션 `HealthCheckEventCleanupScheduler`: 매일 04:30 리더 노드가 `retention-days`(기본 30) 경과 이력 삭제. 진행 중(미복구) 장애는 `last_failed_at`이 계속 갱신돼 삭제되지 않는다.

### 다중 노드 처리 — `HealthClusterSnapshot` / `ClusterView`
- 리소스%·ws·피드 신선도·L4 등 "현재값"은 리더만 갱신한다. 리더가 원시 하트비트 + 자원값을 스냅샷으로 발행하면, 비리더 보드가 이를 읽어 **노드와 무관하게 동일한 화면**을 그린다(장애 이벤트는 공유 DB라 어느 노드서든 동일).
- 공유키(leader·ws-reconnect)는 순간 flap 여지가 있으나 실용상 무해. 보안 검사는 provider별 키 분리(`ext-virustotal`/`ext-safebrowsing`)로 상태 뒤집힘을 해소.

## 새 하트비트 체크 추가 방법 (3단계)
1. `HealthCheckCatalog`에 한 줄 추가 — `HealthSource.HEARTBEAT` + 경고/다운 임계 초(주기의 약 2.5×/5×)를 같은 줄에 선언. `HealthHeartbeatConfig` 등록과 보드 임계 문구가 카탈로그에서 **자동 파생**(별도 등록 없음).
2. 대상 서비스에 계측 한 줄: 성공 지점 `healthHeartbeat.beat(KEY)`, 실패 지점 `healthHeartbeat.fail(KEY, cause)`. 리더 전용 잡은 리더에서만 beat → 비리더는 UNKNOWN(대기)로 남아 오탐 없음. try/catch가 침습적이면 fail 생략 가능(watchdog staleness가 다운 포착).
3. 착수 전 `gitnexus_impact({target, direction:"upstream"})`로 영향도 확인. 특히 `sched-leader-election`은 의존 많은 HIGH 대상이라 신중히.

## 설정 프로퍼티
- `monitor.health.recent-window-hours`(기본 24) — 현재 정상이어도 이 시간 내 복구 장애가 있으면 "최근이상" 흔적 표시. 0 이하면 비활성.
- `monitor.health.retention-days`(기본 30) — 이력 삭제 기준.
- `monitor.health.alert-enabled`(기본 true) — 텔레그램 발송 스위치.
- `monitor.alert-history.source-env`(기본 활성 프로파일) — 이력 환경 구분(prod/local 정규화).

## 연관 도메인
- 프론트: `fe-page-health`(`/admin/health` 보드 화면 — 이 API의 단일 소비자). 자원 스냅샷은 `fe-page-monitor`가 쓰는 `MetricCollectorService`와 공유.
- 백엔드: `be-binance`(피드와 활성 스케줄러 잡들이 하트비트를 계측), Redis 리더 선출(`LeaderElectionService`), 텔레그램(`TelegramProvider`), 챗봇 RAG용 Postgres(pgvector) 연결이 `infra-postgres` 대상. 상세 관계는 `index.md`.
