# 프론트 교차영역: api

> wiki-refresh로 실제 소스를 읽고 검증함(2026-06-24). 페이지 간 관계는 `index.md` 참고.

## 한 줄 요약
프론트의 HTTP 클라이언트(axios) 2종과, `/admin/test` 화면 전용 API 래퍼 모음. 인증 쿠키 자동 전송·503 과부하 감지를 공통 처리하고, 엔드포인트 URL 문자열은 각 도메인 래퍼 파일 안에만 둔다.

## 이런 걸 물을 때 찾으면 된다 (검색 키워드)
- "API 호출은 어떤 클라이언트로 해? axios withCredentials"
- "서버 과부하 503 처리 / server-overloaded 이벤트"
- "관리자 테스트 화면 API / 재색인·로그·아카이브·rawWriter 호출"
- "외부 API(Binance) 호출은 왜 클라이언트가 따로야? CORS"
- "로그인/로그아웃/토큰 갱신 엔드포인트"

## 핵심 개념·용어
- **apiClient**: `withCredentials: true` axios 인스턴스. httpOnly 인증 쿠키를 자동 첨부. 우리 백엔드 호출 전용.
- **externalClient**: `withCredentials` 없는 axios. Binance 등 `Access-Control-Allow-Origin: *`를 주는 서드파티는 자격증명 포함 요청이 브라우저에서 차단되므로 분리.
- **server-overloaded**: 503 응답 시 apiClient가 발생시키는 전역 커스텀 이벤트(`OverloadToast`가 수신).
- **adminTest 래퍼**: `/admin/test` 화면에서만 import하는 API 함수 모음. URL을 화면 코드에 흩지 않기 위한 컨벤션.

## 구조 / 흐름 (확인된 코드 기준)

파일 위치: `frontend/src/api/`

### 공통 클라이언트
- `apiClient.js`: `axios.create({ withCredentials: true })`. 응답 인터셉터에서 `status === 503`이면 `window.dispatchEvent(new CustomEvent('server-overloaded'))` 후 에러 재throw.
- `externalClient.js`: `axios.create()`(자격증명 없음). 서드파티 CORS 와일드카드 대응.

### adminTest 래퍼 — `api/adminTest/`
- **auth.js**: `login(creds)` `POST /api/auth/login`, `refreshAccessToken()` `POST /api/auth/refresh`, `logout()` `POST /api/auth/logout`, `fetchCookieDebug()` `GET /api/admin/test/auth/debug/cookie-info`.
- **chatbot.js** (백엔드 `be-chatbot`의 프로덕션 API를 그대로 호출):
  - `startChatbotReindex()` `POST /api/admin/chatbot/reindex`(전체)
  - `startChatbotDocsReindex()` `POST /api/admin/chatbot/reindex/docs`(문서만 증분 — wiki-refresh가 쓰는 것)
  - `startChatbotDomainReindex(domain)` `POST /api/admin/chatbot/reindex/domain/{domain}`
  - `fetchChatbotReindexStatus(jobId)` `GET /api/admin/chatbot/reindex/{id}` → `{status, processedChunks, totalChunks, documentCount, error}`
  - `askChatbot(question)` `POST /api/chat` → `{answer, sources}`
  - `fetchChatbotLogSummary(params)` `GET /logs/summary`, `fetchChatbotLogTurns(params)` `GET /logs/turns`(Spring Page), `fetchChatbotLogTurnDetail(id)` `GET /logs/turns/{id}`
- **archive.js** (백엔드 `be-binance` 아카이빙): `fetchArchiveCount(startMs,endMs)` `POST /api/admin/archive/count`, `runArchive` `POST /run`(S3 업로드+DB 삭제 전체), `runArchiveUpload` `POST /upload`(업로드+`archive_log` INSERT only, 삭제 없음), `fetchScanPreview()` `GET /scan-preview`, `runScan()` `POST /scan`(S3 스캔→`s3_archive_log` 초기화). startMs inclusive / endMs exclusive(Unix ms).
- **rawWriter.js** (Kafka RawWriter 관측, base `/api/admin/test/agg-trade/raw-writer/`): `fetchRawWriterDryRunSummaries()`, `fetchRawWriterShadowComparison(minutes=60, graceSeconds=20)`, `fetchRawWriterShadowComparisonWindows(minutes=[5,15,60,180], graceSeconds=20)`, `fetchRawWriterKafkaObservability()`, `fetchRawWriterKafkaObservabilityWindows(minutes=60, bucketSeconds=60)`.
- **regressionApi.js** (인증 오인 회귀 검증, `validateStatus: () => true`로 에러도 응답 수신): `fetchSupportSseBadGuestToken()` `GET /api/support/reply/sse?guestToken=not-a-valid-uuid`(잘못된 토큰이 `AUTH_LOGIN_FAILED`로 오인되지 않는지), `fetchBinanceTradesBadDate()` `GET /api/binance/trades?from=2024/01/15`(400이 인증오류로 오인되지 않는지).

## 연관 도메인
- 백엔드: `be-chatbot`(재색인·로그·`/api/chat`), `be-binance`(아카이브·rawWriter). 503 토스트는 `fe-components`의 `OverloadToast`, 챗봇은 `fe-components`의 `FloatingChatbot`. 상세 관계는 `index.md`.
