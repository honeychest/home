# 프론트 교차영역: api

> 이 문서는 로컬 LLM(gemma-4-26b-a4b-it-mlx)이 소스 코드를 근거로 자동 생성했다. 검증 전 초안이다.

## 목차
- 개요 및 클라이언트 구성
- 인증 및 세션 관리
- 아카이빙 프로세스 제어
- 챗봇 재색인 및 질의응답
- Raw Writer 데이터 분석 및 관측성
- 회귀 테스트 및 예외 케이스 검증

## 개요 및 클라이언트 구성

본 프로젝트의 API 클라이언트는 `frontend/src/api/apiClient.js`와 `frontend/src/api/externalClient.js` 두 가지 유형으로 구성됩니다.

`frontend/src/api/apiClient.js`는 `withCredentials: true` 설정이 적용된 axios 인스턴스로, 요청 시 httpOnly 인증용 쿠키를 자동으로 포함하여 전송합니다. 또한 응답 인터셉터를 통해 HTTP 503 에러 발생 시 `server-overloaded` 커스텀 이벤트를 발생시키는 로직을 포함하고 있습니다. 해당 클라이언트는 `frontend/src/api/adminTest/` 디렉토리 내의 API 래퍼들(`archive.js`, `auth.js`, `chatbot.js`, `rawWriter.js`, `regressionApi.js`)에서 사용됩니다.

반면, `frontend/src/api/externalClient.js`는 `withCredentials` 설정이 없는 외부 API 전용 클라이언트입니다. 이는 Binance 등 CORS wildcard(*) 응답을 반환하는 서드파티 API 호출 시, `withCredentials: true` 설정으로 인해 브라우저가 차단되는 문제를 방지하기 위해 분리되어 있습니다.

## 인증 및 세션 관리

`apiClient.js`에서 생성된 `apiClient`는 요청 시 쿠키를 자동으로 포함하여 전송하도록 설정되어 있어, 이를 통해 `httpOnly` 인증용 쿠키를 활용한 세션 관리가 이루어집니다.

인증 관련 기능은 `auth.js`를 통해 다음과 같이 제공됩니다.
*   **로그인**: `login(credentials)` 메서드를 호출하여 `/api/auth/login` 엔드포인트로 인증 정보를 전달합니다. (`frontend/src/api/adminTest/auth.js`)
*   **액세스 토큰 갱신**: `refreshAccessToken()` 메서드를 통해 `/api/auth/refresh` 엔드포인트로 토큰 갱신을 요청합니다. (`frontend/src/api/adminTest/auth.js`)
*   **로그아웃**: `logout()` 메서드를 호출하여 `/api/auth/logout` 엔드포인트로 로그아웃을 수행합니다. (`frontend/src/api/adminTest/auth.js`)
*   **쿠키 디버깅**: `fetchCookieDebug()` 메서드를 통해 `/api/admin/test/auth/debug/cookie-info` 엔드포인트에서 쿠키 정보를 조회할 수 있습니다. (`frontend/src/api/adminTest/auth.js`)

## 아카이빙 프로세스 제어

아카이빙 대상 건수를 조회하기 위해 `startMs`(Unix ms, inclusive)와 `endMs`(Unix ms, exclusive)를 인자로 전달하여 호출합니다. (`frontend/src/api/adminTest/archive.js`)

아카이빙 실행은 S3 업로드와 DB 삭제를 포함하는 전체 프로세스로, `startMs`와 `endMs`를 인자로 전달하여 수행합니다. (`frontend/src/api/adminTest/archive.js`)

S3 업로드와 `archive_log`에 `complete='N'` 상태로 데이터를 INSERT하는 프로세스입니다. 이 작업은 DB 삭제를 포함하지 않습니다. (`frontend/src/api/adminTest/archive.js`)

S3 파일 목록을 미리보기 위한 기능으로, DB에 별도의 INSERT 작업이 발생하지 않습니다. (`frontend/src/api/adminTest/archive.js`)

S3에 존재하는 기존 파일을 스캔하여 `s3_archive_log`를 초기화하는 1회용 작업입니다. (`frontend/src/api/adminTest/archive.js`)

## 챗봇 재색인 및 질의응답

재색인 작업은 `startChatbotReindex` 함수를 호출하여 시작하며, 성공 시 202 상태 코드와 함께 `jobId`를 반환합니다(`frontend/src/api/adminTest/chatbot.js`). 재색인 작업의 진행 상태는 `fetchChatbotReindexStatus` 함수를 통해 관찰할 수 있으며, 반환 데이터에는 `status`, `processedChunks`, `totalChunks`, `documentCount`, `error` 정보가 포함됩니다(`frontend/src/api/adminTest/chatbot.js`). 코드베이스에 대한 질의응답은 `askChatbot` 함수를 통해 수행하며, 호출 시 질문(`question`)을 인자로 전달하고 `answer`와 `sources`를 반환받습니다(`frontend/src/api/adminTest/chatbot.js`).

## Raw Writer 데이터 분석 및 관측성

`frontend/src/api/adminTest/rawWriter.js` 파일에 정의된 API를 통해 다음과 같은 데이터 분석 및 관측성 기능을 제공합니다.

*   **Dry Run 요약 조회**: `fetchRawWriterDryRunSummaries` 메서드를 통해 Raw Writer의 Dry Run 요약 정보를 조회합니다.
*   **Shadow Comparison (섀도우 비교)**: 
    *   `fetchRawWriterShadowComparison` 메서드를 통해 특정 시간 범위(`minutes`)와 유예 시간(`graceSeconds`)을 기준으로 섀도우 비교 데이터를 조회합니다.
    *   `fetchRawWriterShadowComparisonWindows` 메서드를 통해 설정된 시간 윈도우(`minutes`)와 유예 시간(`graceSeconds`)을 기준으로 섀도우 비교 데이터를 조회합니다.
*   **Kafka 관측성 (Observability)**:
    *   `fetchRawWriterKafkaObservability` 메서드를 통해 Kafka 관측성 데이터를 조회합니다.
    *   `fetchRawWriterKafkaObservabilityWindows` 메서드를 통해 특정 시간 범위(`minutes`)와 버킷 단위(`bucketSeconds`)를 기준으로 Kafka 관측성 윈도우 데이터를 조회합니다.

## 회귀 테스트 및 예외 케이스 검증

`regressionApi.js`를 통해 특정 상황에서의 예외 처리 및 인증 오작동 여부를 검증합니다.

*   **잘못된 Guest Token 처리**: `fetchSupportSseBadGuestToken` 메서드를 통해 잘못된 `guestToken`(`not-a-valid-uuid`)을 전달하여, SSE 구독 시 발생할 수 있는 `IllegalArgumentException` 상황에서 인증 실패(`AUTH_LOGIN_FAILED`)로 오인되지 않는지 확인합니다. (`frontend/src/api/adminTest/regressionApi.js`)
*   **잘못된 날짜 파라미터 처리**: `fetchBinanceTradesBadDate` 메서드를 통해 잘못된 날짜 포맷(`2024/01/15`)을 전달하여, 400 에러 발생 시 해당 응답이 인증 관련 오류로 오인되지 않는지 검증합니다. (`frontend/src/api/adminTest/regressionApi.js`)
