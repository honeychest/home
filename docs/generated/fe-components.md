# 프론트 교차영역: components

> 이 문서는 로컬 LLM(gemma-4-26b-it-mlx)이 소스 코드를 근거로 자동 생성했다. 검증 전 초안이다.

## 목차
- 개요
- 실시간 모니터링 및 게이지 시각화
- 알림 이력 조회 및 필터링 시스템
- 뉴스 피드 데이터 연동 및 로컬 캐싱 전략
- 서버 과부하 감지 및 전역 토스트 알림
- 챗봇 위젯 인터페이스 및 메시징 흐름
- API 통신 및 에러 핸들링 정책

## 개요

이 도메인은 시스템 모니터링 데이터 시각화, 알림 이력 관리, 뉴스 피드 제공 및 사용자 인터랙션을 위한 챗봇 위젯을 포함하는 프론트엔드 컴포넌트 집합입니다.

주요 기능은 다음과 같습니다:
- **모니터링 및 시각화**: `GaugeBar.jsx`는 수치 데이터에 따라 색상과 스타일이 변하는 게이지 바를 제공하며, `AlertHistoryTable.jsx`는 기간 및 타입별 필터링과 페이징 기능이 적용된 알림 이력 테이블을 제공합니다.
- **알림 및 상태 피드백**: `OverloadToast.jsx`는 서버 과부하 시 사용자에게 알림을 표시하며, `NewsFeed.jsx`는 외부 API를 통해 최신 뉴스를 가져와 목록 형태로 제공합니다.
- **사용자 인터랙션**: `FloatingChatbot.jsx`는 페이지 우측 하단에 상주하며 사용자의 질문에 답변하는 챗봇 위젯을 제공합니다.

## 실시간 모니터링 및 게이지 시각화

모니터링 데이터의 시각화 및 이력 관리는 `GaugeBar.jsx`와 `AlertHistoryTable.jsx`를 통해 수행됩니다.

`GaugeBar.jsx`는 수치 데이터의 임계값에 따라 시각적 상태를 변화시키는 컴포넌트입니다. `clamp` 함수를 통해 입력값을 0에서 100 사이로 제한하며, `colorFor` 함수는 값의 범위에 따라 색상을 결정합니다(70 미만: `var(--monitor-gauge-ok)`, 90 미만: `var(--monitor-gauge-warn)`, 90 이상: `var(--monitor-gauge-critical)`). 또한 `alertInfo` 함수는 임계값에 따라 테두리(`styles.cardBorder`), 배경색(`styles.cardBg`), 번짐 효과(`styles.cardPulse`)를 단계적으로 적용하여 시각적 경고를 제공합니다.

`AlertHistoryTable.jsx`는 발생한 알림의 이력을 필터링 및 페이징하여 제공합니다. 사용자는 `from`, `to` 날짜 범위와 `type`(CPU, RAM, DISK, REDIS_QUEUE, API_ERROR)을 설정하여 특정 조건의 이력을 검색할 수 있습니다. 테이블은 `fmt` 함수를 통해 `sentAt` 시각을 KST(Asia/Seoul) 기준으로 포맷팅하여 표시하며, 각 행은 지표 종류(`metricType`), 현재값, 임계값, 지속 시간(분 단위), 심각도(`severity`) 정보를 포함합니다. 데이터는 `apiClient.get('/api/monitor/alert-history', { params })` 호출을 통해 서버로부터 가져옵니다.

## 알림 이력 조회 및 필터링 시스템

`AlertHistoryTable.jsx` 파일에 구현된 알림 이력 조회 및 필터링 시스템은 다음과 같은 기능을 제공합니다.

**1. 데이터 조회 및 페이징**
`apiClient.get('/api/monitor/alert-history', { params })`를 호출하여 알림 이력 데이터를 가져옵니다. `params` 객체는 `page`(페이지 번호)와 `size`(20으로 고정)를 포함하며, 사용자가 설정한 필터 조건(`query.from`, `query.to`, `query.type`)이 있을 경우 해당 값들이 포함됩니다. 데이터는 `data.content` 배열에 담기며, 페이지 이동을 위해 `totalPages`와 `totalElements` 정보를 활용합니다.

**2. 필터링 기능**
사용자는 세 가지 조건으로 이력을 검색할 수 있습니다.
* **기간 필터**: `draft.from`과 `draft.to` 입력창을 통해 시작일과 종료일을 설정합니다. 기본값은 현재 날짜와 일주일 전 날짜로 설정되어 있습니다.
* **타입 필터**: `draft.type` 선택 상자를 통해 특정 지표(CPU, RAM, DISK, REDIS_QUEUE, API_ERROR)를 선택하거나 '전체'를 선택할 수 있습니다.
* **검색 적용**: 사용자가 필터 값을 변경하면 `draft` 상태에 저장되며, '검색' 버튼을 클릭하는 시점에 `query` 상태로 반영되어 API 요청이 발생합니다. 검색 시 페이지 번호는 0으로 초기화됩니다.

**3. 데이터 표시 및 포맷팅**
* **발생 시각**: `fmt` 함수를 통해 서버에서 전달된 시간을 한국 표준시(KST) 기준으로 포맷팅하여 표시합니다.
* **지표 및 값**: `metricType`과 수치 데이터(`value`, `threshold`)를 출력합니다. 수치는 소수점 첫째 자리까지 표시되도록 처리되어 있습니다.
* **지속 시간**: `row.durationSec` 값을 기반으로 분 단위로 계산하여 표시합니다.
* **심각도**: `row.severity` 값에 따라 스타일이 구분됩니다. 특히 'CRITICAL'인 경우 별도의 클래스가 적용됩니다.

## 뉴스 피드 데이터 연동 및 로컬 캐싱 전략

`apiClient.get('/api/news')`를 통해 뉴스 데이터를 가져오며, 데이터 로드 실패 시 3초 간격으로 재시도하되 최대 5회(`MAX_RETRY`)까지 시도한 후 중단하는 재시도 로직을 포함합니다(`frontend/src/components/monitor/NewsFeed.jsx`). 성공적으로 데이터를 불러오면 5분(`REFRESH_INTERVAL`)마다 자동으로 갱신됩니다(`frontend/src/components/monitor/NewsFeed.jsx`).

읽은 뉴스 항목의 상태 관리는 `localStorage`를 활용한 로컬 캐싱 전략을 사용합니다(`frontend/src/components/monitor/NewsFeed.jsx`). 뉴스 링크를 클릭하면 `markAsRead` 함수가 호출되어 해당 링크와 타임스탬프가 `READ_STORAGE_KEY`('newsfeed_read')로 저장됩니다(`frontend/src/components/monitor/NewsFeed.jsx`). 캐싱된 데이터는 `loadReadLinks` 함수를 통해 로드되며, 이때 24시간(`READ_TTL_MS`)이 경과하여 만료된 항목은 필터링되어 제거됩니다(`frontend/src/components/monitor/NewsFeed.jsx`).

## 서버 과부하 감지 및 전역 토스트 알림

`server-overloaded` 커스텀 이벤트를 감지하여 사용자에게 서버 과부하 상태를 알리는 토스트 UI를 제공합니다. `frontend/src/components/toast/OverloadToast.jsx`에서 정의된 이 컴포넌트는 `window` 객체에 등록된 해당 이벤트 핸들러를 통해 동작하며, 이벤트 발생 시 `visible` 상태를 `true`로 변경합니다. 토스트는 화면 상단 중앙에 고정되어 나타나며, 4초 후에 자동으로 사라지도록 설정되어 있습니다.

## 챗봇 위젯 인터페이스 및 메시징 흐름

`FloatingChatbot` 컴포넌트는 모든 페이지 우측 하단에 상주하는 플로팅 위젯으로, `open` 상태에 따라 채팅 패널의 표시 여부가 결정됩니다 (`frontend/src/components/chatbot/FloatingChatbot.jsx`).

사용자가 질문을 입력하고 전송 버튼을 누르거나 `Enter` 키를 입력하면 `send` 메서드가 실행됩니다 (`frontend/src/components/chatbot/FloatingChatbot.jsx`). `send` 메서드는 다음과 같은 흐름으로 동작합니다:

1.  **사용자 메시지 반영**: 입력된 질문을 `messages` 상태에 `{ role: 'user', text: q }` 형태로 즉시 추가합니다 (`frontend/src/components/chatbot/FloatingChatbot.jsx`).
2.  **API 호출**: `apiClient.post('/api/chat', { question: q })`를 통해 백엔드에 질문을 전달합니다 (`frontend/src/components/chatbot/FloatingChatbot.jsx`).
3.  **봇 응답 처리**: 
    * API 호출이 성공하면, 응답 데이터(`res.data`)에서 `answer`를 텍스트로, `sources`를 출처 목록으로 추출하여 `{ role: 'bot', text: data.answer || '(답변 없음)', sources: ... }` 형태로 `messages` 상태에 추가합니다 (`frontend/src/components/chatbot/FloatingChatbot.jsx`).
    * API 호출 중 오류가 발생하면, 에러 메시지를 봇의 응답 형태로 `messages` 상태에 추가합니다 (`frontend/src/components/chatbot/FloatingChatbot.jsx`).
4.  **UI 업데이트**: 새로운 메시지가 추가되면 `messagesEndRef`를 사용하여 스크롤을 최하단으로 이동시킵니다 (`frontend/src/components/chatbot/FloatingChatbot.jsx`).

메시지 목록은 `messages` 배열을 순회하며 렌더링되며, 각 메시지의 `role` 값에 따라 사용자(`user`)는 우측 정렬된 파란색 말풍선으로, 봇(`bot`)은 좌측 정렬된 흰색 말풍선으로 표시됩니다 (`frontend/src/components/chatbot/FloatingChatbot.jsx`).

## API 통신 및 에러 핸들링 정책

- `apiClient`를 통한 비동기 통신 시, 성공 시에는 데이터를 상태에 반영하고 실패 시에는 에러 메시지를 사용자에게 노출하거나 빈 데이터로 초기화하는 정책을 사용한다.
- `FloatingChatbot.jsx`에서는 `/api/chat` 호출 중 에러 발생 시, 응답 상태 코드(`err.response.status`) 또는 에러 메시지(`err.message`)를 추출하여 챗봇 대화창 내에 직접 표시한다.
- `AlertHistoryTable.jsx`에서는 `/api/monitor/alert-history` 호출 실패 시, 데이터 상태를 빈 배열과 0으로 구성된 객체(`{ content: [], totalPages: 0, totalElements: 0 }`)로 설정하여 빈 테이블을 렌더링한다.
- `NewsFeed.jsx`에서는 `/api/news` 호출 실패 시, 최대 5회(`MAX_RETRY`)까지 3초 간격(`RETRY_INTERVAL`)으로 재시도를 수행한다. 5회 실패 시에는 로딩 상태를 종료하고 에러 상태(`setFailed(true)`)로 전환하여 사용자에게 알림을 제공한다. 또한, 컴포넌트 언마운트 시 `cancelled.current`를 통해 진행 중인 비동기 작업의 결과 반영을 방지한다.
- `OverloadToast.jsx`에서는 브라우저 전역 이벤트인 `server-overloaded`가 발생할 경우, 서버 과부하 상황을 알리는 토스트 메시지를 4초간 노출한다.
