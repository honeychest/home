# 프론트 교차영역: components

> wiki-refresh로 실제 소스를 읽고 검증함(2026-06-24). 페이지 간 관계는 `index.md` 참고.

## 한 줄 요약
여러 페이지에서 전역으로 쓰는 독립 위젯 모음: 모니터링 게이지·알림이력 테이블·뉴스피드, 서버 과부하 토스트, 그리고 모든 페이지 우하단의 코드베이스 챗봇 위젯.

## 이런 걸 물을 때 찾으면 된다 (검색 키워드)
- "플로팅 챗봇 위젯은 어떻게 동작해? /api/chat 호출"
- "챗봇이 현재 페이지/세션을 어떻게 보내? pageId sessionId history"
- "모니터 게이지 색상 임계값 / GaugeBar"
- "알림 이력 필터/페이징 / alert-history"
- "뉴스피드 재시도/읽음 처리 / 서버 과부하 토스트"

## 핵심 개념·용어
- **FloatingChatbot**: `App` 전역에 상주하는 플로팅 챗봇 위젯. Router 바깥이라 `window.location.pathname`으로 현재 페이지를 직접 읽는다.
- **pageId/sessionId/history**: 챗봇이 백엔드에 함께 보내는 맥락. pageId는 경로에서 파생, sessionId는 localStorage 영구값, history는 최근 12개 대화.
- **server-overloaded 토스트**: 503 시 apiClient가 쏜 전역 이벤트를 받아 띄우는 과부하 안내.
- **GaugeBar 임계값**: 70/80/90 구간으로 색·강조가 달라지는 모니터 게이지.

## 구조 / 흐름 (확인된 코드 기준)

파일 위치: `frontend/src/components/`

### 코드베이스 챗봇 위젯 — `chatbot/FloatingChatbot.jsx`
- 우하단 FAB로 패널 토글. 독립 CSS Module(앱 shadcn/Tailwind 토큰에 비의존).
- 전송 `send()`: `POST /api/chat`에 `{ question, history, pageId, sessionId }` 전송.
  - `history`: `messages` 최근 12개를 `{role:'user'|'assistant', content}`로 변환(서버는 저장 안 함, 맥락용).
  - `pageId`: `derivePageId(window.location.pathname)` — `/signal,/analysis,/binance,/trade,/logistics,/monitor,/weather,/winner|/random→random,/admin`, 모르면 null.
  - `sessionId`: localStorage `chs-chatbot-session-id`(없으면 `crypto.randomUUID()` 생성·저장).
- 응답 `{answer, sources}`를 봇 말풍선으로 추가. 출처는 `SourceList`가 파일명만(`fileNameOf`) 접이식으로 표시. 오류 시 `오류: {status|message}` 말풍선.
- Enter 전송 / Shift+Enter 줄바꿈. 로딩 중 "답변 생성 중...(로컬 AI 모델이라 수십 초)" 안내. (백엔드는 `be-chatbot`의 `ChatbotController.ask(question, history, pageId, sessionId)`.)

### 모니터 게이지 — `monitor/GaugeBar.jsx`
- `clamp(v)`: 0~100, 숫자 아니면 null(스켈레톤). `colorFor`: <70 `--monitor-gauge-ok`, <90 `--monitor-gauge-warn`, ≥90 `--monitor-gauge-critical`.
- `alertInfo`: 70%+ 테두리(`cardBorder`), 80%+ 배경(`cardBg`), 90%+ 번짐(`cardPulse`) 단계 적용. `value==null`이면 `--` 표시.

### 알림 이력 테이블 — `monitor/AlertHistoryTable.jsx`
- `GET /api/monitor/alert-history` params `{page, size:20, from, to, type}`. type: `CPU/RAM/DISK/REDIS_QUEUE/API_ERROR`(빈 값=전체).
- `draft`(입력) → '검색' 시 `query`로 반영(page 0 리셋). 기본 기간 = 일주일 전~오늘.
- 행: `sentAt`(KST `fmt`), `metricType`, `value`/`threshold`(소수1자리), `durationSec`→분, `severity`(CRITICAL 별도 스타일). 실패 시 `{content:[], totalPages:0, totalElements:0}`.

### 뉴스피드 — `monitor/NewsFeed.jsx` (PC 사이드바)
- `GET /api/news`. 실패 시 `RETRY_INTERVAL=3000`마다 최대 `MAX_RETRY=5`회 재시도, 성공 후 `REFRESH_INTERVAL=5분`마다 갱신.
- 읽음 캐시: localStorage `newsfeed_read`(키), `READ_TTL_MS=24시간` 경과분은 제거. `markAsRead`/`loadReadLinks`. 링크는 `http(s)`만 새 탭(`noopener,noreferrer`). `fmtAgo`(초/분/시간 전), 카테고리 배지(경제/IT/인기/최신).

### 서버 과부하 토스트 — `toast/OverLoadToast.jsx`
- `window` `server-overloaded` 이벤트 수신 → 4초간 상단중앙 토스트("서버 과부하(CPU 95%이상)로 모든 API요청을 중단합니다..."). (이벤트 발생원은 `fe-api`의 apiClient 503 인터셉터.)

## 연관 도메인
- 백엔드: `be-chatbot`(`/api/chat`), 모니터링/뉴스 API(global). 클라이언트·토스트 연계는 `fe-api`. 챗봇 위젯은 `be-chatbot`의 pageId 가중·로그와 한 쌍. 상세 관계는 `index.md`.
