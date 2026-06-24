# 프론트 페이지: admin

> wiki-refresh로 실제 소스를 읽고 검증함(2026-06-24). 페이지 간 관계는 `index.md` 참고.

## 역할 요약
- **한 줄 정의** — 방문자 로그, 허용 IP, 데이터 품질 진단·수동 수집·롤업 등을 다루는 운영자 전용 관리 페이지.
- **누가·언제 쓰나** — 운영자가 데이터 건강성/갭을 점검하고 수동 수집·롤업을 돌리거나, 방문자 로그·Feature Flag를 관리할 때. (`/admin/login` 인증 필요)
- **핵심 기능** — ① 데이터 품질 진단(Flat/Outlier)·갭 조회·수동 수집 ② 롤업 배치 관리 ③ 방문자 로그·허용 IP·Feature Flags + 테스트 도메인(인증·아카이브·Kafka·챗봇).

## 이런 걸 물을 때 찾으면 된다 (검색 키워드)
- "관리자 페이지 / 운영자 화면 / admin 권한 로그인"
- "데이터 갭 조회 / 수동 백필 수집 / 롤업 1s 1m 5m"
- "Flat Outlier 보정 / 데이터 품질 진단"
- "방문자 로그 / 허용 IP / Feature Flags"
- "admin test 탭 / 인증·아카이브·rawWriter·챗봇 재색인"

## 핵심 개념·용어
- **권한 정책**: `useAdminAuth`의 `canAccess`/`isForbidden`. 없으면 `shouldRedirectToAdminLogin`로 `/admin/login` 이동.
- **Flat/Outlier**: raw 대비 1s 불일치(Flat)·이상치(Outlier) 진단·보정(FUTURES만 보정 실행).
- **갭(gap)**: 데이터 누락 구간. ID 기반(`RAW_AGG_TRADE`) 또는 시간 기반.
- **백필(backfill) 수집**: 갭을 채우는 수동 수집 작업(`jobs`로 상태 추적, RUNNING이면 3초 폴링).
- **Feature Flags**: 기능 토글(`tradeThresholdEdit`, `monitorAllowedIpManage`).

## 구조 / 흐름 (확인된 코드 기준)

파일 위치: `frontend/src/page/admin/`

### 진입·권한 — `AdminPage.jsx`, `adminAccessPolicy.js`, `login/AdminLoginPage.jsx`
- `useAdminAuth`의 `canAccess`/`isForbidden`로 접근 제어, `shouldRedirectToAdminLogin`→`/admin/login`. 로그인 성공 시 `useAdminAuth().refresh`로 상태 갱신 후 이전 경로 복귀. 테스트 레이아웃 `test/AdminTestLayout.jsx`도 동일 정책.

### 데이터 품질 — `hooks/useDataHealth.js`, `hooks/useOutlier.js`, `constants.js`
- 대상은 `SYMBOLS`/`MARKETS`, 범위는 `healthHours`. **Flat**: `handleHealthCheck`(불일치 조회), `handleDeleteFlat`(`tableKey` 초기화), `handleFlatCorrectionHealth`/`handleFlatCorrection`(FUTURES 보정; `correctionResult`에 삭제 1m·생성 5m 건수). **Outlier**: `OUTLIER_RANGE_OPTIONS` 범위, `handleOutlierCorrectionHealth`/`handleOutlierCorrection`(FUTURES만). Outlier는 `healthHours`를 공유, 시작 시 `resetGapView`.

### 갭 조회·수동 수집 — `hooks/useDataGap.js`, `api/adminApi.js`
- `CHECKS`(RAW_AGG_TRADE/AGG_1M/AGG_5M/OI)+`days`로 `getDataGapCheck`. `ID_BASED`(RAW_AGG_TRADE) 여부로 `gap_start_id/end_id` vs `gap_start_ms/end_ms` 컬럼. 체크 선택 후 `handleBulkCollect`: `symbol`+`market_type` 그룹화→ID/시간 페이로드로 `postBackfillCollect`. 완료 시 `getBackfillJobs`+갭 재조회.

### 롤업·배치 — `hooks/useRollup.js`, `hooks/useManualCollect.js`
- `postAggtradeRollup`(1s→1m→5m, `rFrom`/`rTo`, 결과 `inserted1m/inserted5m`/`ok`). 수동 수집 `postBackfillCollect`(타입별 ID/시간). `jobs`에 RUNNING 있으면 `setInterval` 3초 폴링.

### 방문자 로그·IP·플래그 — `hooks/useVisitorLogs.js`, `sections/VisitorLogsCard.jsx`, `hooks/useFeatureFlags.js`
- `getVisitorLogs`→`VisitorLogsCard`: `topPaths`(경로별 `cnt`), `recent`(visitedAt·IP·path; visitedAt은 'T'→공백·앞 19자). 로딩/에러 상태.
- Feature Flags 초기값 `{ tradeThresholdEdit: true, monitorAllowedIpManage: false }`. `monitorAllowedIpManage` true면 `AllowedIpsCard` 렌더. 변경 `patchFeatureFlags`.

### 테스트 도메인 — `test/AdminTestLayout.jsx` (`TEST_TABS`)
- **인증/아카이브**(`test/AuthTestPage.jsx`, `auth-test/*`): `logApiCall`로 `POST /api/auth/login`, `GET /api/admin/test/auth/debug/cookie-info`, 아카이브 `handleArchiveCount/Run/Upload`, 스캔 `handleScanPreview/Run`. 결과 `ResultPanel`, busy 제어 `runningAction`. (API는 `fe-api`의 `auth.js`/`archive.js`.)
- **Kafka 텔레메트리**(`test/RawWriterTestPage.jsx`): `snapshot`(mode/listenerRunning/오프셋 latest·committed·lag), `summary`(totalConsumed/WriteSuccess/DlqPublished, worstWindow), `windows`(분·초 버킷). (API는 `fe-api`의 `rawWriter.js`.)
- **챗봇 재색인**(`test/ChatbotTestPage.jsx`): `startChatbotReindex`+`fetchChatbotReindexStatus` 폴링(status/processedChunks/totalChunks), `askChatbot`(answer/sources). (API는 `fe-api`의 `chatbot.js` — docs/도메인 재색인·로그 API도 동일 파일.)

## 연관 도메인
- 권한·레이아웃은 `fe-shared`, 테스트 API는 `fe-api`. 백엔드: 데이터/롤업/방문자/`be-chatbot`(재색인·로그). 상세 관계는 `index.md`.
