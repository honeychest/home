# 프론트 페이지: logistics

> wiki-refresh로 실제 소스를 읽고 검증함(2026-06-24). 페이지 간 관계는 `index.md` 참고.

## 역할 요약
- **한 줄 정의** — OMS·WMS·QMS·TMS 등 물류 작업 흐름과 예외 상황을 시뮬레이션하고 단계별로 시각화하는 관제 페이지.
- **누가·언제 쓰나** — 물류 프로세스(주문→창고→품질→운송)의 흐름과 예외 복구를 시뮬레이션으로 관찰·제어하고 싶을 때.
- **핵심 기능** — ① Task/Event 기반 시뮬레이션 틱 루프 ② 단계 그리드/레인 시각화 ③ 예외 주입·복구 등 운영자 액션 + 로그 이력 추적.

## 이런 걸 물을 때 찾으면 된다 (검색 키워드)
- "물류 시뮬레이션 페이지 / OMS WMS QMS TMS 관제"
- "태스크 진행률 / 틱 루프 / 자동 모드 auto"
- "예외 주입 / 복구 액션 recovery / branch inject"
- "단계 그리드 레인 / 워크노드 카드 시각화"
- "이벤트 로그 / 이력 체인 / 감사 로그 / 초기화 reset"

## 핵심 개념·용어
- **Task / Event**: 시뮬레이션의 작업 단위(`LogisticsTask`)와 그 상태변화 기록(`LogisticEvent`). 정의·저장은 `fe-domain-logistics`/`fe-store`.
- **틱 루프(tickLoop)**: 작업을 단계별로 전진시키는 루프. 진행률 `progressPercent = ticksInCurrentStage/ticksTarget`.
- **복구(Recovery) / 예외 주입(Branch Inject)**: 운영자가 실패를 복구하거나 일부러 실패를 주입하는 액션.
- **Focus**: 현재 관찰 중인 작업(`focusStore`). 로그·상세가 이 작업 중심으로 필터링됨.
- **시뮬레이션 설정**: `globalFailureRate`(전역 예외율) + `stageOverrides`(단계별). `localStorage` 영속.

## 구조 / 흐름 (확인된 코드 기준)

파일 위치: `frontend/src/page/logistics/` (69개 파일 — 아래는 핵심 골격)

### 진입·레이아웃 — `LogisticsPage.jsx`, `LogisticsLayout.jsx`
- `LogisticsLayout`이 데스크톱(`LogisticsDashboard`)/모바일(`LogisticsMobileDashboard`)을 전환하고 각 도메인 컨슈머 라이프사이클을 제어. `useWindowState`가 `max-width:1024px` 미디어쿼리로 `narrowScreen` 판단, `handleDesktopViewOpen`로 모바일에서 데스크톱 뷰 강제 전환.
- 탭 상태 `useTabState`(`localStorage` `logistics.activeTab`). `FocusArea`/`RightPanel`이 선택 작업 상세·이력·복구/주입 UI.

### 데이터 모델·상태 — (저장은 `fe-store`)
- `taskStore`/`eventStore`/`focusStore` + `emitter`로 상태 동기화(`logistics:task:updated`, `logistics:focus:changed`, `logistics:event` 등). KPI/헬스 집계는 `useLogisticsHeaderSnapshot`/`useLogisticsSnapshot`.
- 이력 체인: 같은 `aggregateId`(=taskId) 이벤트를 시간순 정렬. `isFailureEvent`/`isRecoveryEvent`로 실패/복구 구분.

### 시뮬레이션 엔진 — `hooks/simulation/useAutoMode.js`, `utils/taskHelpers.js`
- `startTickLoop`/`stopTickLoop`. `autoMode`면 신규 작업(OMS/EOS) 자동 생성, 기존 작업은 계속 전진. `progressPercent`(completed/failed=100%), `withLiveProgress`(paused 5%·failed 95% 보정).

### 단계 시각화 — `StageWorkGrid` / `StageWorkLane` / `WorkNodeCard`
- 단계별 레인에 세부 노드를 배치, `nodeTasks`로 노드별 작업 수·상태(active/paused/failed) 표시. `dotState`/`connState`/`RouteStrip`으로 단계 연결(done/current). 도메인별 그리드: OMS 3열, QMS 5열, INBOUND 6열 등(`logistics-grid-N`).

### 예외 처리·운영자 액션 — `services/recoveryActions.js`, `components/focus/FocusWorkPanel.jsx`
- 실패 작업: `task.failureActions`로 `performRecoveryAction`(→`active` 또는 terminal `cancelled/disposed/returned`, `targetReceiveNodeKey` 재설정, `task.recovered` 이벤트).
- 진행 작업: `getFailureCandidatesForStage`로 후보 추출→`performBranchInject`(기존 삭제 후 `failed`로 전환, `buildInjectedFailureEvent` 기록).

### 로그·이력 — `components/log/LogOverlayContent.jsx`, `logDomain.js`
- `logistics:event`/`retention:cleared`로 실시간 갱신. `getLogDomain`(payload.stage/routingKey→OMS/WMS/TMS/AUDIT). 이벤트 선택 시 `aggregateId`를 포커스로. `isFocusScope`면 현재 작업 이력만. 라벨 `historyEventLabel`.

### 설정·초기화 — `hooks/simulation/useSimulationSettings.js`, `hooks/useLogisticsReset.js`
- `globalFailureRate`+`stageOverrides`(`SettingsOverlay` 고급보기), `saveSimulationSettings`로 `localStorage` 저장. `handleProgressReset`(진행·이벤트 초기화)/`handleFullReset`(탭 포함 전체→`overview`). 초기화 시 `audit.reset.performed` 감사 로그.

## 연관 도메인
- `fe-domain-logistics`(타입·큐·워크노드·실패 정의), `fe-store`(IndexedDB 영속). 순수 프론트(백엔드 의존 없음). 상세 관계는 `index.md`.
