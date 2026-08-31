# 시스템 헬스 체크 보드 (Critical Health Board)

시스템 유지·관리를 위한 독립 점검 페이지. 크리티컬 체크를 한 화면에 모아
"전부 OK"를 확인하고, 실패 시 발생 시각·원인·로그를 저장/분석한다.

> **이 문서는 개발자용 설계 원본**(코드 주석·화면 인수인계 패널이 참조)입니다.
> **챗봇용 상세 문서는 `docs/generated/be-health.md`(백엔드)·`docs/generated/fe-page-health.md`(화면)** 로 분리되어 있습니다.
> 소스와 대조된 최신 상세 설명이 필요하면 그쪽을 보세요(챗봇 doc 레이어 색인 대상은 `docs/generated/`뿐).

- 페이지: `/admin/health` (로그인 필요, 독립 라우트)
- 백엔드 API: `/api/admin/health/**` (`ADMIN_ACCESS` 권한 자동 보호)
- 실패 이력 저장: `health_check_event` 테이블 (범용 checkKey 기반)

## 설계 결정

- **가) 방식 A** — Spring Boot HealthIndicator 표준 패턴 + 실패이력 저장 레이어.
- **나) 페이지·뼈대 우선** → 세부 체크는 이후 하나씩 계측하며 채움.
- **다) 독립 라우트** `/admin/health`.
- **라) 통합** — 신규 `health_check_event`(범용) 신설. 기존 `AlertHistory`(수치
  임계 알림 전용, enum 고정)는 텔레그램 알림용으로 유지하되, 실패를 이 테이블로
  합류시킨다. enum 고정 문제 해소 + 관심사 분리.
- **agentRunner(Codex runner) 체크는 제외.** lab(home) 기준만.

## 저장 대상 원칙

실시간 틱이 초당 수백 건 도는 시스템이므로 **정상(OK)은 저장하지 않고 현재상태만**,
저장은 **"체크가 FAIL로 바뀐 이벤트 + 원인/스택트레이스 요약 + 복구 시각"** 으로 한정.

## 마스터 체크리스트

현재 카탈로그는 30개 체크(하트비트 10개)로 운영한다.

표기: ○이미 신호 있음 · △부분 · ✕없음(신규 계측 필요) / P0=치명 P1=중요 P2=여유

| key | 계층 | P | 대상 | 신호 |
|-----|------|---|------|------|
| infra-mysql | L1 인프라 | P0 | MySQL 연결·응답 | △ actuator |
| infra-redis | L1 인프라 | P0 | Redis 연결·응답 | △ actuator |
| infra-kafka | L1 인프라 | P0 | Kafka 브로커 연결 | ✕ |
| infra-postgres | L1 인프라 | P0 | Postgres(pgvector) 연결 | ✕ |
| feed-binance-ticker | L2 피드 | P0 | binance-ticker freshness | ○ FeedHealthRegistry |
| feed-binance-aggtrade | L2 피드 | P0 | binance-aggTrade freshness | ○ |
| feed-upbit | L2 피드 | P1 | upbit freshness | ○ |
| feed-ws-reconnect | L2 피드 | P1 | WS 재연결 루프 상태 | △ |
| pipe-rollup-1s | L3 파이프라인 | P0 | 1초 롤업 최근 성공 | ✕ |
| pipe-rollup-1m | L3 파이프라인 | P0 | 1분 롤업 최근 성공 | ✕ |
| pipe-rollup-5m | L3 파이프라인 | P1 | 5분 롤업 최근 성공 | ✕ |
| pipe-empty-candle-fix | L3 파이프라인 | P1 | 빈캔들 교정(5분) 성공 | ✕ |
| data-candle-gap | L4 무결성 | P1 | 캔들 gap 없음 | ○ DataGapCard |
| data-quality | L4 무결성 | P1 | 데이터 품질 | ○ DataQualityCard |
| sched-leader-election | L5 스케줄러 | P0 | Redis 리더 선출(5s) | △ |
| sched-weather | L5 스케줄러 | P1 | weather(cron 10분) 최근 성공 | ✕ |
| sched-news | L5 스케줄러 | P1 | news RSS(5분) 최근 성공 | ✕ |
| sched-telegram-poll | L5 스케줄러 | P1 | telegram 폴링(30s) 최근 성공 | ✕ |
| sched-openinterest-poll | L5 스케줄러 | P1 | OpenInterest 폴링(60s) 성공 | ✕ |
| sched-analysis | L5 스케줄러 | P1 | analysis 탐지(60s) 성공 | ✕ |
| ext-telegram-send | L6 외부연동 | P1 | Telegram 송신 성공/실패 | ✕ |
| ext-llm | L6 외부연동 | P1 | LLM 채팅·임베딩 응답 | ✕ |
| ext-weather-api | L6 외부연동 | P2 | Weather API 호출 | ✕ |
| ext-news-rss | L6 외부연동 | P2 | News 원본 RSS 응답 | ✕ |
| ext-virustotal | L6 외부연동 | P2 | VirusTotal 파일 검사 | ✕ |
| ext-safebrowsing | L6 외부연동 | P2 | SafeBrowsing URL 검사 | ✕ |
| res-cpu | L7 리소스 | P1 | CPU 임계 | ○ AlertService |
| res-ram | L7 리소스 | P1 | RAM 임계 | ○ |
| res-disk | L7 리소스 | P1 | DISK 임계 | ○ |
| res-ws-connections | L7 리소스 | P2 | WS 연결수 이상 | △ |

## 진행 현황

> **최신 진행 현황(완료/남은 항목, 계측 상태)은 화면 `/admin/health` 하단
> "작업 인수인계" 패널이 단일 소스다.** (전 항목 계측 완료로 "구현 로드맵" 패널은 제거됨.)
> 이 문서는 변하지 않는 설계·체크리스트·패턴 참조용(챗봇용)이며, 진행 수치는 여기에 두지 않는다(드리프트 방지).

## 새 하트비트 체크 추가 방법 (3단계)

1. `HealthCheckCatalog`에 항목을 `HealthSource.HEARTBEAT` 소스로 한 줄 선언 —
   경고/다운 임계 초(주기의 약 2.5×/5×)를 같은 줄에 함께 선언한다.
   `HealthHeartbeatConfig`의 하트비트 등록과 보드 임계 문구는 카탈로그에서 자동 파생(별도 등록 없음).
2. 대상 서비스: 성공 지점 `healthHeartbeat.beat(KEY)`, 실패 지점 `healthHeartbeat.fail(KEY, cause)`.
   - 리더 전용 잡은 리더에서만 beat → 비리더는 UNKNOWN(대기)로 남아 오탐 없음.
   - try/catch 추가가 침습적이면 fail 생략 가능(watchdog staleness가 다운 포착).
3. 착수 전 `gitnexus_impact({target, direction:"upstream"})`로 영향도 확인·보고.

## 구현 순서 가이드 (패턴별)

착수 후보(전 항목 계측 완료 — 이력 참조용): 인프라 프로브(L1),
리소스 스냅샷 재사용(L7), 데이터 쿼리(L4), 외부연동(L6),
그리고 별도 신중 처리 대상 `sched-leader-election`(HIGH).

## 계측 규약 (2차 이후)

각 서비스에 heartbeat 한 줄을 심는다.
- 성공: `healthCheckRecorder.markOk("pipe-rollup-1m")`
- 실패: `healthCheckRecorder.markFail("pipe-rollup-1m", cause)` → `health_check_event` 적립
