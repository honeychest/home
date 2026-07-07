# docs/generated 인덱스 (챗봇 지식 카탈로그)

> 이 파일은 전체 문서 카탈로그 + 도메인 관계 맵 + 용어집 안내를 한곳에 모은다.
> 각 페이지는 벡터 검색용이라 그 자체로 self-contained하게 쓰고, 페이지 간 연결은 여기서만 관리한다.
> wiki-refresh 진행 상황은 `log.md` 참고. 최근 갱신: 2026-07-06 (헬스 체크 보드 신규 문서화 — `be-health.md` + `fe-page-health.md` 추가. 기존: 백엔드 5 + FE 도메인 4 + FE 공용 4 + FE 페이지 8 + 용어집 1). ⚠ 신규 2개 문서는 아직 벡터 미반영 — `POST /api/admin/chatbot/reindex/docs` 1회 필요.

## 카탈로그

### 백엔드 도메인 (소스 검증 완료 — 2026-06-24)
- `be-weather.md` — 전국 10개 시도 날씨를 DB 우선으로 제공, 부족분만 기상청 API 호출·저장. Redis 호출량 카운트.
- `be-upbit.md` — Upbit WebSocket 고정 5코드 always-on 구독 → 프론트 세션별 코드 필터링 전달. 캐시 스냅샷.
- `be-analysis.md` — 조건 트리(거래량/가격/델타/시간) 템플릿 저장·평가, 1분 주기 리더 노드 자동 탐지(SSE), 수동 탐색.
- `be-chatbot.md` — 코드베이스 RAG 챗봇. pageId 가중검색·맥락보강·로그적재·3종 재색인(전체/문서/도메인).
- `be-binance.md` — 체결·틱·청산·OI 수집 → 1s/1m/5m 롤업·백필 → S3 아카이빙 → 시그널 SSE/WS 브로드캐스트.
- `be-health.md` — 시스템 헬스 체크 보드 백엔드. 7계층 34체크 상시 점검, FAIL/복구만 `health_check_event` 적립, DOWN 텔레그램 알림. `/api/admin/health/**`.

### 공통
- `domain-glossary.md` — 프로젝트 공통 용어집. 용어 뜻을 물을 때 1차 참조.
- (참고) 옛 `chatbot.md`는 `be-chatbot.md`와 중복이라 2026-06-24 통합·삭제됨. 챗봇 백엔드는 `be-chatbot.md` 하나로 본다.

### 프론트 도메인 (소스 검증 완료 — 2026-06-24)
- `fe-domain-binance.md` — 바이낸스 시세(WS)·체결/틱/시그널(SSE) 수신, 업비트 KRW 비교·김치 프리미엄 계산, 지갑은 REST 1회.
- `fe-domain-logistics.md` — 브라우저 인메모리 큐로 물류 프로세스(OMS/WMS/QMS/TMS/AFT/EOS/INBOUND) 이벤트 시뮬레이션.
- `fe-domain-support.md` — guestToken 기반 관리자 문의 팝업(텍스트+이미지 압축), 문의 내역·답변·테마.
- `fe-domain-weather.md` — Cesium 3D 지구본에 기온 상대 색상 시각화, 지역 클릭 상세.

### 프론트 공용 (소스 검증 완료 — 2026-06-24)
- `fe-api.md` — axios 클라이언트 2종(apiClient/externalClient)·503 과부하 이벤트, `/admin/test` API 래퍼(auth/chatbot/archive/rawWriter/regression).
- `fe-store.md` — 물류 시뮬레이션 영속 계층(Dexie IndexedDB): tasks/events 저장, 보관 한도, 감사로그, 포커스.
- `fe-components.md` — 전역 위젯: FloatingChatbot(/api/chat), GaugeBar·AlertHistoryTable·NewsFeed(모니터), OverLoadToast.
- `fe-shared.md` — 관리자 인증(컨텍스트·훅), 공통 상수/유틸(cn·formatWithComma·useMediaQuery), 레이아웃(Header/Footer/Layout)·MiniChart·shadcn.

### 프론트 페이지 (소스 검증 완료 — 2026-06-24)
- `fe-page-signal.md` — 실시간 에너지/청산/OI 단타 신호 대시보드(스텔스 패턴·TugOfWar).
- `fe-page-analysis.md` — 복합 조건으로 과거 패턴 탐색·하이라이트·템플릿 저장.
- `fe-page-binance.md` — 바이낸스×업비트 시세 비교·김치 프리미엄·지갑.
- `fe-page-trade.md` — 실시간 BTC 체결/틱 모니터·대형 체결 강조·조회 필터.
- `fe-page-logistics.md` — 물류 프로세스 시뮬레이션 관제(틱 루프·예외 주입/복구).
- `fe-page-monitor.md` — 리소스/Docker/Redis/피드 실시간 모니터링.
- `fe-page-random.md` — Matter.js 물리 추첨(/winner)·랭킹·편집기.
- `fe-page-admin.md` — 운영자 데이터 진단·수집·롤업·로그·테스트 도메인.
- `fe-page-health.md` — 시스템 헬스 체크 보드 화면(`/admin/health`): 7계층 카드·상태 점·팝오버·이상 필터.

## 도메인 관계 맵

페이지(pageId) ↔ 백엔드 도메인 대응은 `PageContextRegistry`(검증됨) 기준:

```
 프론트 페이지(pageId)        주요 백엔드 도메인
 ───────────────────────────────────────────────
 signal     →  be-binance (시그널 데이터/SSE)
 analysis   →  be-analysis  +  be-binance(1m/5m봉·delta)
 binance    →  be-binance  +  be-upbit (시세 비교)
 trade      →  be-binance (RawTick/체결)
 weather    →  be-weather
 monitor    →  (global 모니터링; 백엔드 도메인 외)
 logistics  →  (프론트 시뮬레이션 중심)
 random     →  (프론트 전용)
 admin      →  be-chatbot(로그/테스트) + 운영 API들
 health     →  be-health (/api/admin/health/** — 34체크 판정·이력·알림)
```

데이터 의존 방향(백엔드):
```
 be-binance ──(1m/5m봉, delta, SignalSseService)──▶ be-analysis
 be-binance ──(BinanceWebSocketStream 공용 인프라)──▶ be-upbit
 be-chatbot ──(docs/generated/*.md 를 doc 레이어로 색인)──▶ 이 문서들 전체
 be-binance ──(피드/롤업/아카이브 잡이 healthHeartbeat.beat/fail 계측)──▶ be-health
 be-health  ──(infra-postgres 프로브가 챗봇 RAG용 pgvector 연결 점검)──▶ be-chatbot
```

FE 도메인 → 백엔드 대응:
```
 fe-domain-binance ──(WS /ws/binance-price, SSE /api/binance·/api/signal)──▶ be-binance
 fe-domain-binance ──(WS /ws/upbit-price 중계)──────────────────────────────▶ be-upbit
 fe-domain-weather ──(/api/weather/available-hours, /api/weather/all)───────▶ be-weather
 fe-domain-support ──(/api/support/inquiry·inquiries·reply)─────────────────▶ (support 백엔드)
 fe-domain-logistics ── 순수 프론트 시뮬레이션(백엔드 의존 없음)
```

FE 공용 ↔ 다른 영역:
```
 fe-api(apiClient) ──503──▶ fe-components(OverLoadToast)
 fe-api(apiClient) ───────▶ 모든 도메인/페이지의 HTTP 호출 공통 진입점
 fe-components(FloatingChatbot) ──/api/chat {question,history,pageId,sessionId}──▶ be-chatbot
 fe-store(IndexedDB) ◀──저장── fe-domain-logistics(LogisticsTask/Event/emitter)
 fe-shared(Layout) ──문의 SSE/visitor/log──▶ fe-domain-support, be(visitor)
```

## 용어집 안내
- 공통 용어(델타, OI, 청산, 롤업, 백필, RAG, layer 등) 정의는 `domain-glossary.md`와 각 `be-*.md`의 "핵심 개념·용어" 절에 있다.

## 알려진 불일치 (lint, 2026-06-24)
- **be-upbit**: `UpbitSubscriptionChangeEvent` 클래스는 존재하나 발행처·수신처가 없어 **현재 미연결(dead code)**. "동적 구독 갱신" 흐름은 동작 안 함 → 문서는 실제(고정 전체 구독)로 기술함. 코드 정리 시 이벤트 제거 또는 배선 필요.
- **chatbot.md vs be-chatbot.md (해결됨 2026-06-24)**: 옛 `chatbot.md`는 `be-chatbot.md`의 오래된 부분집합(신기능 누락 + "근거 경로를 본문에 제시" 등 현재 코드와 반대인 서술)이라, 누락분(외부 CLI 실패 흡수·PgVector indexType=NONE)만 `be-chatbot.md`에 흡수하고 삭제함.
- **이전 생성본 깨진 글자**: be-binance/be-chatbot/be-upbit, fe-domain-logistics/support 등에서 로컬 LLM이 끼워 넣은 잔여 문자("립", "break", "session코드가", "sringboot", "sTMS_", "OMS_VALIDated", "IN^OUND", "consumemr", "frontend/s/" 등)를 재작성에서 제거함.
- **fe-domain 정정(2026-06-24)**: ① logistics routingKey 실제 형식은 `{prefix}{stage}.{nodeKey}.done`(기존 문서의 `order.received`는 축약/부정확) ② WMS 단계에 `WMS_DISPATCHED/WMS_COMPLETED` 포함, TaskType은 `ORDER|INBOUND|EOS` ③ support 엔드포인트는 `/inquiry`,`/inquiries`,`/reply/{id}/read` 3종 ④ BINANCE_MARKETS는 BTC/ETH/SOL/XRP 4종(기존 문서가 BTC 위주로 서술).
- **fe-공용 정정(2026-06-24)**: ① `FloatingChatbot`은 `/api/chat`에 `{question, history, pageId, sessionId}`를 보낸다(기존 문서는 question만 — stale). be-chatbot의 `ask(question, history, pageId, sessionId)`와 한 쌍. ② `fe-api/chatbot.js`에 문서/도메인 재색인·로그 3종 API가 추가됨(기존 문서엔 일부만). ③ `fe-store`는 물류 영속 계층(IndexedDB)이고 `fe-domain-logistics`의 `InMemoryQueue`(휘발 pub/sub)와 별개 — 혼동 금지.
- **fe-page 검증(2026-06-24)**: 페이지별 상수 소스 확인 — signal 버퍼(TRADE 20/LIQ 50/OI 5000), trade(`USD_KRW_RATE=1450`·`HIGHLIGHT_DURATION_MS=500`·임계값 1~10,000,000), random(보드 1040×960·`BALL_RADIUS=29`·`MAX_BALL_SPEED=18`·`MAX_MENUS=10`·`MENU_STORAGE_KEY='random-pachinko-menus'`), monitor(`/ws/monitor`·`/api/monitor/snapshot`·2초 재연결), admin(플래그 `{tradeThresholdEdit:true, monitorAllowedIpManage:false}`·수집 폴링 3초). 깨진 글자(`frontend/s/`,`레이아out`,`apilet.post`,`BINANCE_MARKets`,`tradedisplayModel`,`기반 Co`) 제거.
- **남은 TODO**: doc 레이어 재색인 1회(`POST /api/admin/chatbot/reindex/docs`) — 문서 변경을 챗봇에 반영. 참고: 각 page 소스 폴더에 co-located 설계문서(`signal-page.md` 등)가 있으나 `docs/generated`가 아니라 챗봇 색인 대상은 아님.
