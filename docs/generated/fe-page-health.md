# 프론트 페이지: health (시스템 헬스 체크 보드)

> wiki-refresh로 실제 소스를 읽고 검증함(2026-07-06). 페이지 간 관계는 `index.md` 참고.
> 소스 위치: `frontend/src/page/admin/health/` (HealthBoardPage.jsx · healthApi.js · HealthBoard.module.css).

## 역할 요약
- **한 줄 정의** — 시스템 30개 크리티컬 체크의 현재 상태를 한 화면에 모아 "전부 정상(OK)"을 확인하고, 이상 시 원인·최근 실패 이력을 즉시 보는 운영자 전용 독립 페이지.
- **누가·언제 쓰나** — 운영자가 배포 후 시스템 건강성을 점검하거나, 장애 알림을 받았을 때 어느 계층이 무너졌는지 한눈에 확인할 때. (`/admin/health`, 관리자 로그인 필요)
- **핵심 기능** — ① 7계층 카드 그리드 + 상태 점 + 팝오버(판정근거·임계·최근 실패 3건) ② 상단 요약 상태바(총/OK/경고/다운/대기 + '전부 정상' 표시) ③ "이상 항목만" 필터 ④ 최근 복구 흔적("최근이상") 배지 ⑤ 하단 작업 인수인계·운영 메모 패널.

## 이런 걸 물을 때 찾으면 된다 (검색 키워드)
- "헬스 보드 화면 / admin health 페이지 / 시스템 상태 대시보드"
- "계층 카드 / 상태 점 색 / 팝오버 / 최근 실패 이력 보기"
- "전부 정상 OK 확인 / 이상 항목만 필터 / 새로고침"
- "최근이상 배지 recentlyRecovered / 복구 흔적 주황 링"
- "헬스 API 호출 getHealthChecks / /api/admin/health/checks"

## 핵심 개념·용어
- **상태 4종 표시**: `UP`=OK(초록) · `DEGRADED`=경고(주황, 점 모양 병행) · `DOWN`=다운(빨강, 점 모양 병행) · `UNKNOWN`=대기(회색). 색약 대비로 색+점모양을 함께 쓴다.
- **계층(layer)**: 백엔드 `HealthLayer` 라벨(S1 인프라 ~ S7 리소스)로 그룹핑. `layerCode`(enum name)로 정렬.
- **우선순위**: 치명/중요/여유 — 각 행에 표기.
- **allOk**: 요약의 DOWN·DEGRADED가 모두 0이면 상단 상태바가 초록 "전부 정상 (OK)", 아니면 빨강 "이상 감지".
- **최근이상(recentlyRecovered)**: 현재 정상이지만 최근 창(백엔드 `recent-window-hours` 기본 24h) 안에 복구된 장애가 있던 항목. 라이브 색은 유지하고 주황 링 + "최근이상" 배지만 덧입혀 클릭 없이 인지.
- **pin(고정)**: 행 클릭 시 팝오버를 고정(`pinned` Set) — hover가 풀려도 유지.

## 구조 / 흐름 (확인된 코드 기준)

파일 위치: `frontend/src/page/admin/health/`

### 라우팅·권한 — `MainRouter.jsx`, `HealthBoardPage.jsx`
- 라우트 `/admin/health`(독립 라우트, `MainRouter`에 등록). `Layout`으로 감싸되 `enableSupport={false}`(운영 화면이라 문의 위젯 끔).
- 권한: `useAdminAuth()`의 `canAccess`/`isForbidden`. `shouldRedirectToAdminLogin(...)`이면 `/admin/login`으로 이동(원래 경로를 `state.from`에 보관). `canAccess===null`이면 "접근 권한 확인 중...", 접근 불가면 로그인으로.
- `canAccess`가 true가 되면 `load()`로 체크를 조회.

### API 호출 — `healthApi.js`
- `getHealthChecks()` → `GET /api/admin/health/checks` (`apiClient`, `withCredentials`). 응답 `{ generatedAt, summary, checks }`를 그대로 `data`로 저장.
- `getHealthEvents()` → `GET /api/admin/health/events` (최근 실패 이력 100건 — 현재 페이지 컴포넌트는 checks만 사용, events는 예비 API).
- 에러는 `e.response?.data?.error ?? '조회 실패'`로 표시.

### 화면 구성 — `HealthBoardPage.jsx`
- **상단 상태바**(`summary` 존재 시): "전부 정상 (OK)" 또는 "이상 감지" 헤드라인 + `총/OK/경고/다운/대기` 카운트 + `generatedAt` + "이상 항목만/전체 보기" 토글 버튼. `allOk`에 따라 초록/빨강 배경.
- **계층 카드 그리드**(`groupByLayer`): 체크를 `layerCode` 순서대로 카드로 묶는다. 카드 헤더에 계층 라벨 + 미니 카운트(●다운/●경고/●OK/○대기). 이상이 있으면 카드에 빨강 테두리(`layerCardAlert`).
- **체크 행**(`CheckRow`): 상태 점 + 라벨 + (최근이상 배지) + 우선순위. 클릭하면 팝오버 고정(`togglePin`).
- **팝오버**: 설명(description) · 상태 배지 · **판정근거(detail) + 임계 문구(thresholdText)** · 체크키(mono) · 최근 실패 이력(각 건: 시각 → 복구시각 또는 "진행 중" + 원인). 실패 이력 없으면 "이력 없음".
- **"이상 항목만" 토글**(`onlyAlerts`): DOWN/DEGRADED 행만 남기고 정상 카드는 숨김. 이상이 하나도 없으면 "이상 항목 없음 — 전부 정상".
- **새로고침 버튼**: `load()` 재호출(자동 폴링 없음 — 수동 갱신).
- 테마: `monitor-teal.css`(라이트) 토큰 + `HealthBoard.module.css`. 상태 색은 `STATUS_META`가 monitor 테마 변수를 참조.

### 하단 패널 (운영·개발 참조, 정적 콘텐츠)
- **작업 인수인계(HandoffPanel, 기본 펼침)**: 계측 진행(30/30 완료 — 피드 4·하트비트 10·리소스 4·인프라 4·데이터 2·외부 6), 5종 계측 패턴, 핵심 파일, 새 체크 추가 3단계, 아키텍처 개선 인계. **개발 현황의 단일 소스**.
- **운영 체크리스트·장기 메모(NotesPanel, 기본 접힘)**: 임계값 실측 튜닝 TODO, 운영 주의점(텔레그램 전제·DOWN만 알림·leader 기준 값·리테이션 등), 배포 전 체크리스트.

## 연관 도메인
- 백엔드: `be-health`(이 화면이 소비하는 `/api/admin/health/**` API의 전부 — 30개 체크·판정·이력·알림). 권한·레이아웃은 `fe-shared`(`useAdminAuth`·`Layout`·`adminAccessPolicy`), HTTP는 `fe-api`(`apiClient`).
- 형제 화면: `fe-page-monitor`(리소스/Docker/Redis 실시간 모니터 — 헬스 보드와 다른 화면, 자원 스냅샷 소스는 공유), `fe-page-admin`(데이터 진단·수집·로그·테스트). 상세 관계는 `index.md`.
