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

## [2026-06-24] 재색인 + before/after 평가 — 대상: 전체 doc 레이어, 결과: go
- 사용자가 admin UI에서 `POST /api/admin/chatbot/reindex/docs` 실행 → COMPLETED. doc 레이어 205청크 삭제 → 107청크 재생성(현재 24개·128,115자 ÷ 107 ≈ 1,198자/청크로 정상, 압축 효과).
- 평가(signal pageId): ① "이게 뭐하는 화면이야?" before≈after(둘 다 정확) — 이 질문은 PageContextRegistry.promptHint(코드) 의존이라 둔감. ② "스텔스 패턴 어떻게 탐지?" → Type A/B 정확, 근거에 docs/generated/fe-page-signal.md 등장. ③ "버퍼 크기?" → 20/50/5000 정확, 근거에 fe-page-signal.md 등장. → 재작성 문서가 키워드 질문에서 검색·기여 확인.
- 관찰(개선 후보): pageBoost가 docs/generated 경로엔 안 걸림(소스 경로만) → 일반 질문에서 무관 docs가 끼어듦. co-located 소스 *.md(signal-page.md 등)와 docs/generated 위키 중복. (코드/설정 변경 사안 — 별도 진행)

## [2026-06-24] 용어집 보강 — 대상: domain-glossary.md, 변경요약: 검수필요 3개 확정 + 시스템/파이프라인/RAG 용어 추가
- 확정(소스 검증): 에너지(aggTrade 누적·청산 시 차감), TugOfWar(롱/숏 에너지 줄다리기), Stealth(Type A 거래량급증∩인사이드바=스텔스 거래 / Type B 비도지∩거래량∩작은몸통=스텔스 의심).
- 추가: 인사이드바·도지·김치프리미엄(트레이딩), 롤업·백필·리더노드·SSE·WebSocket·guestToken·routingKey(시스템), RAG·layer·재색인·청킹/SYMBOL_AWARE·pageId/pageBoost(챗봇). 각 항목 일반정의+프로젝트 쓰임, 검증된 수치/식별자 포함.
- ⚠ 이 편집은 위 재색인 **이후**라 아직 벡터에 미반영. 챗봇에 적용하려면 `/reindex/docs` 1회 더 필요. → **아래 [코드] 항목에서 해소됨**.

## [2026-06-24] 코드 — 1번 pageBoost를 docs/generated 위키에 적용 + 문서 정정, 결과: 완료
- `PageContextRegistry.PageInfo`에 `boostPrefixes` 필드 신설(하위호환 4-arg 생성자 유지). 9개 페이지에 짝이 되는 위키 경로를 넓게 매핑(`fe-page-*` + 관련 `be-*`/`fe-domain-*`). `EvidenceRetriever.pagePrefixes`가 `pathPrefixes ∪ boostPrefixes`로 가중. `pathPrefixes`는 불변 → 도메인 재색인 범위(`startDomainReindex`)는 영향 없음(검색 가중 ↔ 재색인 범위 책임 분리).
- 검증: `compileJava`/`compileTestJava` OK, `EvidenceRetrieverTest`·`ChatbotServiceTest` 5통과(테스트 수정 불필요 — 4-arg 생성자 유지 덕), `detect_changes` 변경 심볼이 의도(EvidenceRetriever.pagePrefixes, PageContextRegistry.PAGES/find)와 일치.
- 적용 시점: 검색 가중은 질문 시점 계산 → **앱 재시작만**으로 동작(재색인 불필요).
- `be-chatbot.md`의 PageInfo 설명을 5-arg(boostPrefixes 포함)로 정정.
- 사용자가 `/reindex/docs` 1회 실행 → 107→**113청크**(용어집 보강 + be-chatbot.md 정정 반영). 위 용어집 항목의 미반영 경고 해소.
- 스팟체크(signal, "에너지 게이지 색 구간 어떻게 돼?"): 근거=`EnergyGauge.jsx` + `signal-page.md` + `signal-components.md`. 위키(`fe-page-signal.md`)는 top6 밖 — 상세 질문엔 위키 유사도가 낮아 정상(가중은 소프트 넛지). **co-located 상세문서(`signal-components.md`)가 핵심 근거였음 → 2번(co-located 제외) 보류 판단이 옳았음을 입증**(제외했으면 이 질문 최고 근거를 잃음).
- 관찰: 답변 LLM 생성 ~27초(로컬 gemma, `SLOW_THRESHOLD_MS`=20000 초과 → LATENCY 적재). 검색 자체는 ~1초. 체감 개선 레버는 챗모델 교체(재색인 무관).
- 보류: 2번(중복 아님 확인 → 드롭 권장), 5번 SYMBOL_AWARE 코드 적용(전체 `/reindex` 3시간), 6번 파라미터 튜닝(평가셋 필요), 모델/임베딩 인프라 결정.
