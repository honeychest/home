# wiki-refresh 작업 이력

## [2026-06-24] wiki 재작성 — 대상: 백엔드 5개(be-weather, be-upbit, be-analysis, be-chatbot, be-binance), 변경요약: LLM Wiki 구조(한줄요약·검색키워드·용어·검증된 구조)로 재작성, 소스 대조 검증, index.md/log.md 신설, 사실 모순 수정
- 검증 방식: 각 도메인 실제 .java 소스를 읽어 클래스/메서드/엔드포인트/상수 확인.
  - weather(4), upbit(3), analysis(핵심 5)는 전수에 가깝게 확인. chatbot(핵심 13)은 신규 기능까지 확인.
  - binance(104개)는 클래스/파일명을 현재 트리와 대조(라인 단위 재검증은 일부).
- 주요 정정:
  - be-chatbot: 누락됐던 신기능 반영 — pageId 소프트 가중검색(overFetch×4, pageBoost 0.15), 후속질문 맥락보강, 이어가기 단문 처리, 로그 적재(ChatbotTurn/Conversation/Evidence/Analysis), 재색인 3종(전체/docs/domain), layer(doc/code) 태그, 로그/요약 Admin API.
  - be-upbit: UpbitSubscriptionChangeEvent 미연결(dead) 확인 → "동적 구독 갱신" 흐름 삭제, 실제(고정 전체 구독+세션별 필터)로 기술.
  - be-analysis: groupOperator 기본값 OR, op(GT/GTE/LT/LTE) vs sign(POSITIVE/NEGATIVE) 구분, search timeframe 15m 분기 추가, 정확한 엔드포인트.
  - be-weather: dailyLimit 기본 10000, Redis 키/TTL, 외부 스케줄러 위치(external/WeatherScheduler.java) 명시.
- 신설: index.md(카탈로그+관계맵+lint), log.md.
- 미완(차기 차수): fe-domain(4), fe-shared/api/store/components(4), fe-page(8), chatbot.md 통합 검토.
- 재색인: 이 시점 미실행. 5개 작업 완료 후 `POST /api/admin/chatbot/reindex/docs` 1회로 doc 레이어 증분 색인 예정.

## [2026-06-24] wiki 재작성 — 대상: FE 도메인 4개(fe-domain-binance, fe-domain-logistics, fe-domain-support, fe-domain-weather), 변경요약: LLM Wiki 구조로 재작성, 프론트 소스 대조 검증, 깨진 글자/사실 정정, index.md 갱신
- 검증 방식: 각 도메인 실제 소스(.ts/.tsx/.js/.jsx)를 읽어 훅·상수·엔드포인트·타입 확인.
  - support(2), weather(핵심 3: regions/weatherUtils/useWeatherData) 전수에 가깝게 확인.
  - binance: 훅 5종(WS/SSE) + 표시모델/시장/지갑/상태/패널 확인. logistics: events/stages/queue/advancer/registry/failures/emitter + 컨슈머 7종 패턴 확인.
- 주요 정정:
  - logistics: routingKey 실제 형식 `{prefix}{stage}.{nodeKey}.done`, WMS 6단계(DISPATCHED/COMPLETED 포함), TaskType ORDER|INBOUND|EOS, 컨슈머 구독패턴 표(order/shipment/quality/dispatch/eos/inbound/aft .*), 1 tick=100ms, INBOUND taskType 미지정 이유.
  - support: 엔드포인트 3종(/inquiry, /inquiries, /reply/{id}/read), MAX_LENGTH=300, TARGET_BYTES=8MB, QUALITIES, guestToken 키 chs_guest_token.
  - binance: BINANCE_MARKETS 4종(BTC/ETH/SOL/XRP), SSE/WS 엔드포인트·상수(BATCH_MS/TICKS_MAX/QUEUE_MAX/OUT_DELAY_MS/디바운스), 김치 프리미엄 계산식, 미문서화 파일(liveStatus/tickerPanelStability) 추가.
  - weather: getRelativeColor 5색 보간·getPtyText, CITY_TO_PROVINCE 매핑, useWeatherData 흐름.
- 정리: 깨진 글자(sTMS_/OMS_VALIDated/IN^OUND/consumemr/frontend/s/ 등) 제거.
- 미완(차기 차수): fe-shared/api/store/components(4), fe-page(8), chatbot.md 통합 검토.
- 재색인: 보류(사용자 결정). 프론트 페이지 차수까지 끝낸 뒤 `POST /api/admin/chatbot/reindex/docs` 1회 예정.

## [2026-06-24] wiki 재작성 — 대상: FE 공용 4개(fe-api, fe-store, fe-components, fe-shared), 변경요약: LLM Wiki 구조로 재작성, 프론트 소스 대조 검증, stale 내용 정정, index.md 갱신
- 검증 방식: api(7)·store(5)·components(5)·shared 핵심(인증/레이아웃/유틸/차트) 실제 소스 확인.
- 주요 정정:
  - fe-components: FloatingChatbot이 `/api/chat`에 `{question, history(최근12), pageId(경로파생), sessionId(localStorage)}` 전송(기존 question만 → stale). 출처는 파일명만 표시.
  - fe-api: apiClient(withCredentials+503 이벤트)/externalClient 구분, chatbot.js에 reindex/docs·reindex/domain·logs 3종 추가, archive/rawWriter/regression 정확한 엔드포인트·기본값.
  - fe-store: Dexie LogisticsDB 스키마(tasks/events 인덱스), EVENT_STORE_RETENTION_LIMIT=10000, taskStore/eventStore/auditStore/focusStore 이벤트 발행. InMemoryQueue(휘발)와 별개임을 명시.
  - fe-shared: AdminAuthContext(/api/admin/data-gap/access)·useAdminAccess(/cookie-info 캐싱), Header NAV_ITEMS·X-Server-Name, Layout(visitor/log·reply SSE), MiniChart(lightweight-charts·DEBOUNCE_MS=200), Footer COMMON_TECH.
  - index.md 관계맵에 FE공용↔BE·도메인 흐름, lint에 fe-공용 정정 추가.
- 미완(차기 차수): fe-page(8), chatbot.md ↔ be-chatbot.md 통합 검토.
- 재색인: 보류. 다음(fe-page) 차수까지 끝낸 뒤 `POST /api/admin/chatbot/reindex/docs` 1회 예정.

## [2026-06-24] wiki 재작성 — 대상: FE 페이지 8개(signal/analysis/binance/trade/logistics/monitor/random/admin), 변경요약: LLM Wiki 구조로 재작성, 페이지별 상수/엔드포인트 소스 검증, 깨진 글자 제거, index.md 갱신
- 검증 방식: 각 페이지 진입 컴포넌트 + 핵심 상수/엔드포인트를 소스/타깃 grep으로 확인. 컴포넌트 다수(logistics 69·admin 38)는 골격·핵심만, 라인단위 전수는 아님.
- 검증된 상수: signal(TRADE_BUFFER 20/LIQ 50/OI 5000), trade(USD_KRW_RATE=1450·HIGHLIGHT_DURATION_MS=500·임계값 1~10,000,000 정수), random(BOARD 1040×960·BALL_RADIUS 29·MAX_BALL_SPEED 18·MAX_MENUS 10·MENU_STORAGE_KEY), monitor(/ws/monitor·/api/monitor/snapshot·2s 재연결), admin(featureFlags 초기값·수집 폴링 3s), signal/analysis 엔드포인트(/api/signal/*, /ws/candle, /api/analysis/*).
- 정정: 깨진 글자(frontend/s/, 레이아out, apilet.post, BINANCE_MARKets, tradedisplayModel, 기반 Co) 제거. 페이지 문서를 백엔드/도메인/공용 문서와 상호 참조 정합화.
- **전체 22개 .md 1차 wiki-refresh 완료.** 남은 작업: chatbot.md ↔ be-chatbot.md 통합 검토, 그리고 전체 doc 레이어 재색인 1회.
- 재색인: 여전히 보류(사용자 결정). 모든 문서 작업이 끝났으므로 다음은 `POST /api/admin/chatbot/reindex/docs` 1회 + before/after 평가가 자연스러운 마무리.

## [2026-06-24] 통합 — 대상: chatbot.md → be-chatbot.md, 변경요약: 중복 문서 통합·삭제
- 판단: `chatbot.md`는 `be-chatbot.md`의 오래된 부분집합(pageId 가중·맥락보강·로그·3종 재색인 등 신기능 누락, "근거 파일 경로를 함께 제시하라"는 현재 시스템 프롬프트와 반대인 서술, 깨진 글자 `컨텍스`/`구적적인`). 고유 가치 없음.
- 조치: 누락분 2가지만 `be-chatbot.md`에 흡수 — ① `GitNexusBoundaryProvider`가 외부 CLI 실패를 흡수해 빈 맵 반환(→토큰 폴백), ② PgVector `indexType=NONE`·`initializeSchema(true)`. 청크 메타데이터 `lines`/`symbol`도 명시.
- 삭제: `docs/generated/chatbot.md`(git에 D로 추적, 복구 가능). index.md 카탈로그·lint·관계 갱신.
- 결과: 챗봇 백엔드 문서는 `be-chatbot.md` 단일 소스. RAG 검색에서 중복 청크로 인한 순위 희석 제거.
- 재색인: 보류. (삭제분도 재색인해야 벡터에서 옛 chatbot.md 청크가 빠짐 — clearDocs가 doc 레이어 전체를 지웠다 다시 넣으므로 자연 반영.)
