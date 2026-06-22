# 프론트 페이지: admin

## 역할 요약

**한 줄 정의** — 방문자 로그, 허용 IP, 데이터 품질 진단·수동 수집·롤업 등을 다루는 운영자 전용 관리 페이지.

**누가·언제 쓰나** — 운영자가 데이터 건강성/갭을 점검하고 수동 수집·롤업을 돌리거나, 방문자 로그·Feature Flag를 관리할 때. (`/admin/login` 인증 필요)

**핵심 기능 3가지**
1. 데이터 품질 진단(Flat/Outlier)·갭 조회·수동 수집
2. 롤업(Rollup) 배치 관리
3. 방문자 로그·허용 IP·Feature Flags 관리

---

## 목차
- 개요 및 관리자 권한 정책
- 데이터 품질 진단 및 보정 (Flat/Outlier)
- 갭 조회 및 수동 데이터 수집 프로세스
- 롤업(Rollup) 및 배치 작업 관리
- 실시간 모니터링 및 방문자 로그 분석
- Feature Flags 및 시스템 설정 관리
- 테스트 도메인: 인증 및 아카이빙 검증
- 테스트 도메인: Kafka 텔레메트리 및 챗봇 재색인

## 개요 및 관리자 권한 정책

관리자 페이지는 `frontend/src/page/admin/AdminPage.jsx`를 중심으로 데이터 품질 진단, 수동 수집, 롤업 및 모니터링 기능을 제공합니다. 접근 제어는 `useAdminAuth.js`를 통해 관리되는 `canAccess` 및 `isForbidden` 상태에 따라 결정되며, `frontend/src/page/admin/adminAccessPolicy.js`의 `shouldRedirectToAdminLogin` 함수를 통해 권한이 없는 경우 `/admin/login`으로 리다이렉트됩니다.

로그인 프로세스는 `frontend/src/page/admin/login/AdminLoginPage.jsx`에서 수행되며, 성공 시 `useAdminAuth.js`의 `refresh`를 호출하여 클라이언트 상태를 갱신하고 이전 경로로 복귀합니다. 또한, `frontend/src/page/admin/test/AdminTestLayout.jsx`와 같은 테스트 레이아웃에서도 동일한 정책이 적용되어 접근 권한을 확인합니다.

## 데이터 품질 진단 및 보정 (Flat/Outlier)

raw 대비 1s 불일치 현황을 진단하고 보정하는 기능을 제공합니다. `frontend/src/page/admin/constants.js`의 `SYMBOLS` 및 `MARKETS` 상수를 활용하여 대상 심볼과 마켓을 선택할 수 있으며, `healthHours` 상태를 통해 조회 시간을 설정합니다.

**1. Flat(불일치) 진단 및 보정**
`frontend/src/page/admin/hooks/useDataHealth.js` 훅을 통해 관리됩니다. `handleHealthCheck` 메서드로 지정된 범위 내의 불일치 현황을 조회하며, `handleDeleteFlat`을 통해 특정 테이블 키(`tableKey`)에 대한 데이터를 초기화할 수 있습니다. 또한, `handleFlatCorrectionHealth`로 보정 진단을 수행하고, `handleFlatCorrection`을 통해 FUTURES 마켓에 대한 보정 실행이 가능합니다. 보정 결과는 `correctionResult`를 통해 삭제된 1m 데이터 건수 및 생성/영향을 받은 5m 데이터 건수 등으로 표시됩니다.

**2. Outlier(이상치) 진단 및 보정**
`frontend/src/page/admin/hooks/useOutlier.js` 훅을 통해 관리됩니다. `frontend/src/page/admin/constants.js`의 `OUTLIER_RANGE_OPTIONS`에 정의된 옵션(현재 범위, 직접 지정 등)을 사용하여 진단 범위를 설정합니다. `handleOutlierCorrectionHealth`로 outlier를 진단하며, `handleOutlierCorrection`을 통해 보정을 실행합니다. 

**3. 데이터 흐름 및 연동**
`frontend/src/page/admin/hooks/useOutlier.js`는 `healthHours`를 `frontend/src/page/admin/hooks/useDataHealth.js`로부터 전달받아 범위를 공유하며, 진단 및 보정 시작 시 `resetGapView` 콜백을 호출하여 기존의 갭 조회 결과 화면을 초기화합니다. 보정 실행 시 `outlierMarket`이 'FUTURES'인 경우에만 실행이 가능하도록 제어됩니다.

## 갭 조회 및 수동 데이터 수집 프로세스

`frontend/src/page/admin/constants.js`의 `CHECKS` 상수에 정의된 타입(RAW_AGG_TRADE, AGG_1M, AGG_5M, OI)과 설정된 `days` 값에 따라 `frontend/src/page/admin/api/adminApi.js`의 `getDataGapCheck`를 호출하여 누락 구간을 조회합니다.

조회된 결과 데이터는 `rows` 상태에 저장되며, `frontend/src/page/admin/constants.js`의 `ID_BASED` 설정(RAW_AGG_TRADE) 여부에 따라 `gap_start_id`/`gap_end_id` 또는 `gap_start_ms`/`gap_end_ms` 컬럼을 기준으로 체크박스 표시 여부가 결정됩니다. (`frontend/src/page/admin/hooks/useDataGap.js`)

사용자가 체크박스로 행을 선택한 후 `handleBulkCollect`를 실행하면, 선택된 데이터들은 `symbol`과 `market_type`을 기준으로 그룹화됩니다. (`frontend/src/page/admin/hooks/useDataGap.js`)

그룹화된 각 그룹에 대해 `type`, `symbol`, `marketType`과 함께, ID 기반 타입인 경우 `fromId`/`toId`를, 시간 기반 타입인 경우 `fromMs`/`toMs`를 페이로드로 구성하여 `frontend/src/page/admin/api/adminApi.js`의 `postBackfillCollect`를 호출합니다.

수집 요청이 완료되면 `frontend/src/page/admin/api/adminApi.js`의 `getBackfillJobs`를 통해 최신 작업 목록을 가져와 `setJobs`로 갱신하며, 동시에 `handleCheck`를 재호출하여 변경된 상태를 반영하기 위해 갭 조회 결과를 다시 수행합니다.

## 롤업(Rollup) 및 배치 작업 관리

`frontend/src/page/admin/api/adminApi.js`의 `postAggtradeRollup` API를 통해 1s → 1m → 5m 단계의 수동 롤업을 실행할 수 있습니다. `frontend/src/page/admin/hooks/useRollup.js` 훅에서 관리되는 `rFrom`(시작 시간)과 `rTo`(종료 시간) 값을 기반으로 요청이 수행되며, 실행 결과는 `inserted1m` 및 `inserted5m` 건수와 함께 성공 여부(`ok`)가 반환됩니다.

수동 수집 작업은 `frontend/src/page/admin/api/adminApi.js`의 `postBackfillCollect` API를 통해 수행됩니다. `frontend/src/page/admin/hooks/useManualCollect.js` 훅을 통해 관리되며, 작업 타입(`cType`)에 따라 ID 기반(`ID_BASED`) 또는 시간 기반(datetime-local)으로 범위를 지정하여 요청을 보냅니다.

수집 작업의 상태는 `frontend/src/page/admin/api/adminApi.js`의 `getBackfillJobs` API를 통해 조회되는 `jobs` 목록을 통해 관리됩니다. 작업 상태 중 `RUNNING`인 항목이 존재할 경우, `frontend/src/page/admin/hooks/useManualCollect.js` 훅 내의 `setInterval`을 통해 3초 간격으로 자동 폴링(Polling)하여 최신 상태를 유지합니다.

## 실시간 모니터링 및 방문자 로그 분석

`frontend/src/page/admin/hooks/useVisitorLogs.js` 훅을 통해 관리자 페이지의 방문 현황 데이터를 관리하며, `frontend/src/page/admin/api/adminApi.js`의 `getVisitorLogs` API를 호출하여 로그를 가져옵니다. `frontend/src/page/admin/AdminPage.jsx`에서 `useVisitorLogs` 훅을 호출하여 `visitor` 객체를 생성하고, 이를 `frontend/src/page/admin/sections/VisitorLogsCard.jsx` 컴포넌트에 전달합니다.

`frontend/src/page/admin/sections/VisitorLogsCard.jsx`는 수집된 `visitorData`를 바탕으로 두 가지 정보를 제공합니다:
* **경로별 집계**: `visitorData.topPaths` 배열을 순회하며 각 경로(`path`)와 해당 경로의 방문 횟수(`cnt`)를 테이블 형태로 표시합니다.
* **최근 방문 이력**: `visitorData.recent` 배열을 순회하며 방문 일시(`visitedAt`), IP, 경로를 표시합니다. 이때 `visitedAt` 데이터는 'T' 문자를 공백으로 치환하고 앞의 19글자만 추출하여 시각을 포맷팅합니다.

데이터 로딩 중에는 `visitorLoading` 상태를 통해 '로딩 중...' 메시지를 표시하며, 조회 실패 시 `visitorError`를 통해 에러를 노출합니다.

## Feature Flags 및 시스템 설정 관리

`frontend/src/page/admin/hooks/useFeatureFlags.js` 훅을 통해 관리되는 `flags` 객체는 시스템의 주요 기능을 제어합니다. 해당 훅에 정의된 초기 상태는 `{ tradeThresholdEdit: true, monitorAllowedIpManage: false }`입니다.

주요 설정 항목은 다음과 같습니다:
- **Trade 임계값 변경 UI (`tradeThresholdEdit`)**: `frontend/s/page/admin/sections/FeatureFlagsCard.jsx`에서 해당 플래그의 상태에 따라 체크박스 UI가 제공됩니다.
- **Monitor 허용 IP 관리 (`monitorAllowedIpManage`)**: `frontend/src/page/admin/sections/FeatureFlagsCard.jsx`에서 관리되며, 이 값이 `true`인 경우에만 `frontend/src/page/admin/AdminPage.jsx` 내의 `AllowedIpsCard`가 렌더링됩니다.

설정 변경은 `frontend/src/page/admin/api/adminApi.js`의 `patchFeatureFlags` 메서드를 통해 수행되며, `frontend/src/page/admin/hooks/useFeatureFlags.js`는 해당 API를 호출하여 변경 사항을 반영합니다.

## 테스트 도메인: 인증 및 아카이빙 검증

`frontend/src/page/admin/test/AdminTestLayout.jsx`에서 관리되는 테스트 도메인은 `TEST_TABS` 정의에 따라 다양한 기능을 포함하며, 그 중 인증 및 아카이빙 검증은 `frontend/src/page/admin/test/AuthTestPage.jsx`를 통해 수행됩니다.

**인증 테스트 (Login)**
- `frontend/src/page/admin/test/auth-test/LoginForm.jsx`를 통해 이메일과 비밀번호를 입력하여 로그인을 시도합니다.
- `frontend/src/page/admin/test/auth-test/useAuthTestActions.js`의 `handleLogin` 메서드는 `frontend/src/page/admin/test/shared/logApiCall.js`의 `logApiCall`을 사용하여 `POST /api/auth/login` 요청을 수행하며, 그 결과(상태 코드, 소요 시간, 응답 본문 등)를 `frontend/src/page/admin/test/auth-test/ResultPanel.jsx`에 표시합니다.
- `frontend/src/page/admin/test/auth-test/CookieSnapshotForm.jsx`는 `GET /api/admin/test/auth/debug/cookie-info`를 호출하여 현재 쿠키 상태를 조회하는 기능을 제공합니다.

**아카이빙 및 스캔 테스트 (S3 Archive & Scan)**
- `frontend/src/page/admin/test/auth-test/ArchiveForm.jsx`는 S3 아카이빙 작업을 위한 인터페이스를 제공합니다.
    - `handleArchiveCount`: 설정된 시작/종료 시간 범위 내의 대상 건수를 조회합니다. (`frontend/src/page/admin/test/auth-test/useAuthTestActions.js`)
    - `handleArchiveRun`: 설정된 범위에 대해 아카이빙(업로드 및 삭제)을 실행합니다. (`frontend/src/page/admin/test/auth-test/useAuthTestActions.js`)
    - `handleArchiveUpload`: 데이터 삭제 없이 업로드 작업만 수행합니다. (`frontend/src/page/admin/test/auth-test/useAuthTestActions.js`)
- `frontend/src/page/admin/test/auth-test/ArchiveScanForm.jsx`는 S3 스캔 작업을 수행합니다.
    - `handleScanPreview`: S3 파일 목록을 미리보기 합니다. (`frontend/s/page/admin/test/auth-test/useAuthTestActions.js`)
    - `handleScanRun`: DB 초기화 스캔을 실행합니다. (`frontend/src/page/admin/test/auth-test/useAuthTestActions.js`)
- 모든 작업의 결과는 `frontend/src/page/admin/test/auth-test/ResultPanel.jsx`를 통해 시각화됩니다. 
    - 아카이빙 결과는 성공 여부, 범위, S3 키, 대상/삭제 건수, 소속 시간 등을 상세히 출력합니다. (`frontend/src/page/admin/test/auth-test/ResultPanel.jsx`)
    - 스캔 결과는 삽입/스킵 건수 또는 S3 파일 목록(키, 범위, 완료 여부 등)을 표시합니다. (`frontend/src/page/admin/test/auth-test/ResultPanel.jsx`)
- `frontend/src/page/admin/test/auth-test/useAuthTestActions.js`에서 관리되는 `runningAction` 상태를 통해 현재 진행 중인 작업(busy) 여부를 제어합니다.

## 테스트 도메인: Kafka 텔레메트리 및 챗봇 재색인

`frontend/src/page/admin/test/RawWriterTestPage.jsx` 파일에 정의된 Kafka 텔레메트리 기능은 `Raw Writer` 파이프라인의 소비, 적재 및 실패 현황을 모니터링합니다. `snapshot` 객체를 통해 파이프라인의 실행 모드(`mode`), 리스너 가동 여부(`listenerRunning`), 대상 테이블 및 컨슈머 그룹 정보를 확인할 수 있으며, `rawTopic`과 `dlqTopic`의 파티션별 오프셋(latest, committed, lag) 정보를 제공합니다. 또한 `summary` 객체를 통해 전체 소비량(`totalConsumedRecords`), DB 성공 수(`totalWriteSuccessRecords`), DLq 발행 수(`totalDlqPublishedRecords`) 등의 핵심 지표와 함께, 가장 문제가 발생한 구간인 `worstWindow` 정보를 확인할 수 있습니다. 시간별 처리량은 `windows` 객체를 통해 설정된 분(minutes) 및 초(seconds) 단위 버킷별로 소비량, 성공/실패 건수 등을 상세히 보여줍니다.

`frontend/src/page/admin/test/ChatbotTestPage.jsx` 파일에서는 코드베이스 벡터 재색인과 RAG(Retrieval-Augmented Generation) 질의응답 기능을 제공합니다. `startChatbotReindex`를 통해 재색인 작업을 시작할 수 있으며, `fetchChatbotReindexStatus`를 사용하여 작업의 진행 상태(status), 처리된 청크 수(`processedChunks`), 총 청크 수(`totalChunks`) 등을 실시간으로 폴링하여 확인할 수 있습니다. 재색인된 데이터를 바탕으로 `askChatbot` 함수를 호출하여 질문을 던지면, 생성된 답변(`answer`)과 해당 답변의 근거가 되는 파일 목록(`sources`)을 확인할 수 있습니다.
