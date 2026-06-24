# Chatbot 도메인 (코드베이스 RAG · 백엔드)

> wiki-refresh로 실제 소스를 읽고 검증함(2026-06-24). 페이지 간 관계는 `index.md` 참고.

## 한 줄 요약
이 프로젝트 자신의 코드/문서를 벡터로 색인해두고, 질문이 오면 관련 청크를 검색(RAG)해 LLM이 **근거 기반으로만** 답하게 하는 챗봇이다. 현재 보고 있는 화면(pageId) 가중, 후속질문 맥락 보강, 질문/답변 로그 적재, 3종 재색인(전체·문서·도메인)을 갖췄다.

## 이런 걸 물을 때 찾으면 된다 (검색 키워드)
- "챗봇은 어떻게 답을 만들어? RAG 파이프라인 / 벡터 검색"
- "재색인 / reindex / docs 색인 / 도메인별 증분 색인 차이"
- "현재 페이지 기반 검색 가중 pageId / pageBoost / overFetch"
- "후속질문 '자세히' 맥락 보강 / 이어가기 단문 처리"
- "챗봇 로그 / turns / 응답 지연 / 환각 억제 시스템 프롬프트"
- "PGVector / 임베딩 / 청킹 / SYMBOL_AWARE / GitNexus 심볼 경계"

## 핵심 개념·용어
- **RAG(Retrieval-Augmented Generation)**: 질문과 비슷한 문서 청크를 먼저 검색해 그 내용을 근거로 LLM이 답을 생성하는 방식. 환각을 줄인다.
- **layer(레이어)**: 색인된 청크의 출처 구분 메타데이터. `docs/generated/` 하위 자연어 문서면 `doc`, 그 외 실제 소스코드면 `code`. (이 wiki-refresh가 갱신하는 건 `doc` 레이어.)
- **pageId**: 사용자가 보고 있는 화면 식별자(예: `signal`, `weather`). 프론트(FloatingChatbot)가 라우트에서 뽑아 보낸다.
- **소프트 가중(pageBoost)**: pageId에 해당하는 경로의 청크에 점수를 더해 재정렬. 하드 필터가 아니라 매칭 0건이어도 전역 결과는 그대로.
- **청킹(chunking)**: 파일을 임베딩 단위로 쪼개는 것. 전략 `TOKEN`(토큰 단위) 또는 `SYMBOL_AWARE`(GitNexus 심볼 경계로 메서드/클래스 단위).
- **PGVector**: PostgreSQL 벡터 확장. 임베딩을 `vector_store` 테이블에 저장하고 코사인 유사도 검색.
- **턴(turn)/대화(conversation)**: 질문-답변 1회가 turn, 같은 sessionId 묶음이 conversation.

## 구조 / 흐름 (확인된 코드 기준)

파일 위치: `springboot/src/main/java/com/chs/springboot/domain/chatbot/`

### 질의응답 — `POST /api/chat` (`ChatbotController` → `ChatbotService.ask(question, history, pageId, sessionId)`)
1. **페이지 정보 조회**: `PageContextRegistry.find(pageId)` → 화면 설명(promptHint)·경로 프리픽스.
2. **검색 질의 보강**(`buildSearchQuery`): 직전 사용자 질문 최근 `SEARCH_CONTEXT_TURNS`(=2)개를 현재 질문 앞에 붙여 "자세히 설명해줘" 같은 맥락 의존 후속질문도 올바른 문서를 찾게 한다.
3. **근거 검색**(`EvidenceRetriever.retrieve(searchQuery, pageId)`): 아래 *검색* 참고.
4. **이어가기 단문 처리**(`isContinuation`): "응/그래/자세히/더" 등 짧은 호응이면 LLM 질문도 보강본으로 교체(새 주제면 원문 유지).
5. **페이지 안내문 주입**: `page.promptHint() + (label)`을 `pageContext`로 전달.
6. **답변 생성**(`GroundedAnswerGenerator.generate(llmQuestion, searchQuery, history, pageContext)`).
7. **로그 적재**: 성공/오류를 `ChatbotLogService`에 기록(실패해도 답변은 유지). 응답은 `ChatResponse(answer, sources)`.
- 대화 이력은 최근 `MAX_HISTORY`(=12)개만 Spring AI 메시지로 변환해 맥락용으로 주입.

### 검색(근거 수집) — `EvidenceRetriever.retrieve(question, pageId)`
- `topK`(=6) 그대로 뽑지 않고 `topK * overFetchMultiplier`(=4) = 24개를 과조회(`vectorStore.similaritySearch`).
- pageId 경로 프리픽스로 시작하는 source 청크에는 `pageBoost`(=0.15)를 더한 **유효점수**로 재정렬해 상위 `topK`만 채택(원본 score는 불변, 정렬 시점에만 가산).
- 채택 청크의 `source` 메타데이터를 distinct로 모아 `RetrievedEvidence(documents, sources)` 반환.

### 답변 생성 — `GroundedAnswerGenerator` + `ChatbotConfig`
- `QuestionAnswerAdvisor`(VectorStore)로 `searchQuery` 기준 컨텍스트를 붙이되, 커스텀 `QA_TEMPLATE`로 "근거 종합은 허용, 코드/프로젝트 사실은 근거에 있는 것만, 일반 개념은 '일반적으로~'로 허용, 둘 다 없으면 모름" 규칙을 강제.
- `ChatClient` 빈(`ChatbotConfig.chatbotChatClient`)은 시스템 프롬프트로 같은 2단 분리 규칙(코드 사실 vs 일반 지식)과 "근거 파일 경로는 본문에 적지 마라"를 고정 → 환각 억제. (출처 경로는 화면에서 별도 표시.)
- `pageContext`가 있으면 "이 페이지/여기" 지시어가 그 화면을 가리키도록 질문 앞에 화면 안내를 덧붙인다.
- LLM 백엔드는 LM Studio(OpenAI 호환). `HttpClientConfig`가 HTTP/1.1 고정(평문 HTTP/2 hang 회피).

### 색인 파이프라인 — `CodebaseIndexingService`(오케스트레이션) → `AsyncReindexRunner`(`@Async` 실제 실행)
- 동시 실행은 `AtomicBoolean running` 락으로 1건만 허용(다른 모드도 같은 락 공유, 중복 시 409). 작업은 `ReindexJob`(jobId·상태·진행률).
- `@Async`는 프록시 기반이라 같은 클래스 내부 호출이면 무시되므로, 실행부를 별도 빈(`AsyncReindexRunner`)으로 분리해 반드시 프록시 경유.
- **3가지 재색인 모드**(모두 `POST` 후 `GET /reindex/{jobId}`로 폴링):
  1. `POST /api/admin/chatbot/reindex` — 전체 풀 리빌드. `clear()`(vector_store 전체 삭제) → `collect()`(모든 indexRoots) → 청킹 → write.
  2. `POST /api/admin/chatbot/reindex/docs` — **doc 레이어만 증분**. `clearDocs()`(`metadata->>'layer'='doc'`만 삭제) → `collectDocs()`(`docs/generated`만) → write. 소스코드 벡터는 보존. **← 이 wiki-refresh가 쓰는 엔드포인트.**
  3. `POST /api/admin/chatbot/reindex/domain/{domain}` — 특정 page/domain 소스만 증분. `clearBySourcePrefixes(...)`(해당 source 프리픽스 벡터만 삭제) → `collectDomain(prefixes)` → write. `{domain}`은 PageContextRegistry에 등록된 pageId여야 함(아니면 400).
- **수집**(`CodebaseDocumentSource`): `indexRoots`를 walk하며 `includeExtensions`(.java/.html/.md/.tsx/.jsx/.ts/.js) 파일만, `excludePathPatterns`(에러 화면/템플릿) 제외. 각 Document에 `source`(상대경로)와 `layer`(doc/code) 메타데이터 부여.
- **청킹**(`CodebaseDocumentChunker`): `chunkStrategy=TOKEN`이면 `TokenTextSplitter`. `SYMBOL_AWARE`이면 `SymbolAwareChunker`가 `GitNexusBoundaryProvider`(`npx gitnexus cypher`로 심볼 경계 조회)를 써서 '리프 우선 + gap-fill'로 메서드/클래스 단위 분할, 경계 없음/stale이면 토큰 분할로 폴백. 인접 청크 `overlapTokens`(=64) 오버랩. 청크 메타데이터에 `lines`(줄 범위)·`symbol`(심볼명) 포함. `GitNexusBoundaryProvider`는 외부 CLI 의존이라 타임아웃·파싱오류 등 모든 실패를 흡수해 빈 맵을 반환(→토큰 폴백)하므로 색인이 중단되지 않는다.
- **쓰기**(`VectorIndexWriter`): `batchSize`(=8) 단위로 `vectorStore.add(...)`, 진행률을 `ReindexJob`에 갱신.
- 설정 빈: `ChatbotProperties`(`chs.chatbot.*`) — topK=6, overFetchMultiplier=4, pageBoost=0.15, indexRoots, sourceBase, Reindex(청킹/배치/확장자/제외/전략/gitnexusRepo="lab").
- 벡터 저장소 설정: `PgVectorConfig`/`PgVectorProperties` — MySQL `@Primary`와 충돌 피하려 `pgVectorDataSource`/`pgVectorJdbcTemplate` 분리, 코사인 거리, dimensions=임베딩 차원, `indexType=NONE`(인덱스 없이 정확검색), `initializeSchema(true)`(최초 기동 시 `vector_store` 테이블 자동 생성).

### 현재 화면 컨텍스트 — `PageContextRegistry`
- pageId → `PageInfo(label, promptHint, searchTerms, pathPrefixes, boostPrefixes)` 정적 맵. 등록 pageId: `signal, analysis, binance, trade, logistics, monitor, weather, random, admin`. (`boostPrefixes` 생략 시 빈 목록으로 채우는 하위호환 4-arg 생성자도 있음.)
- `pathPrefixes`(소스 경로)는 EvidenceRetriever의 pageId 소프트 가중 + 도메인 증분 색인 대상 선정에 함께 쓰인다. `boostPrefixes`(짝이 되는 `docs/generated` 위키 문서 경로)는 **검색 가중에만** 더해지고 도메인 재색인 범위에는 영향 없다 — EvidenceRetriever는 `pathPrefixes ∪ boostPrefixes`로 가중한다. 모르는 pageId는 null(무시).

### 로그 적재·조회 — `ChatbotLogService` + Admin API
- `recordSuccess/recordError`(둘 다 `@Transactional`): `findOrCreateConversation(sessionId)` 후 `ChatbotTurn` 저장. 저장 필드: question/answer/searchQuery/llmQuestion/pageContext/status(SUCCESS·ERROR)/issueType(NONE·LATENCY·ERROR)/latencyMs/evidenceCount/errorMessage. 응답 지연 임계 `SLOW_THRESHOLD_MS`(=20000ms) 이상이면 issueType=LATENCY. 근거 청크는 `ChatbotRetrievedEvidence`(rankNo·source·symbol·lineRange·score·preview)로 저장.
- 환경 구분 `sourceEnv`(프로파일)로 분리 집계. 모델: `ChatbotConversation`, `ChatbotTurn`, `ChatbotRetrievedEvidence`, `ChatbotAnalysis`(이슈 분석/제안, `ChatbotAnalysisAuthor`).
- Admin 조회: `GET /api/admin/chatbot/logs/summary`(총건·의심·평균지연·느린건), `GET /api/admin/chatbot/logs/turns`(필터: from/to/pageId/issueType/status/minLatencyMs/keyword + 페이징), `GET /api/admin/chatbot/logs/turns/{id}`(근거·분석 포함 상세).

### 관리 엔드포인트 요약 (`ChatbotAdminController`, `@RequestMapping("/api/admin/chatbot")`)
- `POST /reindex`, `POST /reindex/docs`, `POST /reindex/domain/{domain}`, `GET /reindex/{id}`
- `GET /logs/summary`, `GET /logs/turns`, `GET /logs/turns/{id}`

## 연관 도메인
- 프론트: `fe-page-admin`(챗봇 로그/테스트 탭), FloatingChatbot 위젯(`fe-shared`/`fe-components`). 옛 Thymeleaf `GET /chatbot` 페이지는 제거됨.
- 색인 대상은 프로젝트 전체 소스 + `docs/generated/*.md`. 문서 품질이 답변 품질을 좌우(이 wiki-refresh의 목적). 상세 관계는 `index.md`.
