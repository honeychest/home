# 프론트 페이지: logistics

> 이 문서는 로컬 LLM(gemma-4-26b-a4b-it-mlx)이 소스 코드를 근거로 자동 생성했다. 검증 전 초안이다.

## 역할 요약

**한 줄 정의** — OMS·WMS·TMS 등 물류 작업 흐름과 예외 상황을 시뮬레이션하고 단계별로 시각화하는 관제 페이지.

**누가·언제 쓰나** — 물류 프로세스(주문→창고→운송)의 흐름과 예외 복구를 시뮬레이션으로 관찰·제어하고 싶을 때.

**핵심 기능 3가지**
1. Task/Event 기반 시뮬레이션 틱 루프
2. 단계 그리드/레인 시각화
3. 예외 주입·복구 등 운영자 액션 + 로그 이력 추적

---

## 목차
- 도메인 개요 및 시스템 아키텍처
- 데이터 모델 및 상태 관리 (Task & Event)
- 시뮬레이션 엔진 및 워크플로우 제어 (Tick Loop)
- 대시보드 레이아웃 및 뷰 모드 관리
- 단계별 워크플로우 시각화 (Grid & Lane)
- 예외 처리 및 운영자 액션 (Recovery & Branch Injection)
- 로그 분석 및 이력 체인 추적
- 설정 관리 및 시뮬레이션 제어 (Settings & Reset)

## 도메인 개요 및 시스템 아키텍처

본 시스템은 물류 프로세스 관제를 위한 프론트엔드 애플리케이션으로, 다양한 도메인(OMS, WMS, TMS, EOS, INBOUND, AFT) 간의 복잡한 작업 흐름과 예외 상황을 시각화하고 관리하는 것을 목적으로 합니다.

시스템은 크게 데이터 흐름을 처리하는 도메인 로직, 상태 관리를 위한 스토어, 그리고 사용자 인터페이스를 구성하는 컴포넌트 계층으로 구분됩니다. 데이터는 이벤트 기반 아키텍처를 따르며, `eventStore`와 `taskStore` 등을 통해 작업(Task)의 상태 변화와 이벤트 이력을 관리합니다. 특히 `emitter`를 통한 이벤트 발행/구독 모델을 사용하여 도메인 간의 상태 변화(예: `logistics:focus:changed`, `logistics:task:updated`)를 실시간으로 동기화합니다.

주요 아키텍처 구성 요소는 다음과 같습니다:

1.  **도메인 및 비즈니스 로직 계층**: `stages`와 `failures` 도메인을 통해 각 단계별 작업 노드 정의, 예외 후보군 추출, 상태 라벨링 등의 핵심 비즈니스 규칙을 관리합니다. 시뮬레이션 환경을 위해 `omsSimulation`, `eosSimulation` 등에서 작업 생성 및 예외 주입 로직이 구현되어 있습니다.
2.  **상태 관리 및 데이터 흐름**: `focusStore`를 통해 현재 사용자가 관찰 중인 작업(Focused Task)의 ID를 관리하며, `taskStore`와 `eventStore`는 작업의 생명주기와 이벤트 로그를 영속화합니다. `useLogisticsHeaderSnapshot`과 같은 커스텀 훅은 KPI(오더, 진행중, 실패 건수) 및 도메인별 헬스 체크 상태를 집계하여 상단 헤더에 제공합니다.
3.  **UI/UX 레이아웃 계층**: 
    *   `LogisticsLayout`은 전체 애플리케이션의 진입점으로, 데스크톱과 모바일 뷰를 전환하며 각 도메인별 컨슈머(Consumer)의 라이프사이클을 제어합니다.
    *   `LogisticsDashboard`와 `LogisticsMobileDashboard`는 화면 크기에 따른 레이아웃을 제공하며, 탭 기반의 `TabContent`를 통해 각 도메인별 작업 그리드(Grid)를 렌더링합니다.
    *   `FocusArea`와 `RightPanel`은 선택된 작업에 대한 상세 정보, 이력 체인(History Chain), 그리고 복구 조치(Recovery Action) 및 예외 주입(Branch Inject)과 같은 운영자 액션을 수행하는 핵심 인터페이스 역할을 합니다.
4.  **시뮬레이션 및 운영 제어**: `useAutoMode`를 통해 자동 주문/작업 생성 기능을 제어하며, `SettingsOverlay`와 `useSimulationSettings`를 통해 시스템의 예외율 및 단계별 설정값을 관리합니다. 운영자는 `performRecoveryAction`과 `performBranchInject`를 통해 작업의 상태를 강제로 변경하거나 실패 상황을 시뮬레이션할 수 있습니다.

## 데이터 모델 및 상태 관리 (Task & Event)

이 시스템의 핵심 데이터 모델은 `task`와 `event`로 구성되며, 각 객체는 작업의 상태 변화와 이력 체인을 형성하는 데 사용됩니다.

**1. Task (작업) 모델**
`task` 객체는 물류 프로세스의 최소 단위로, 현재 진행 상태와 도메인 정보를 포함합니다.
*   **상태 관리**: `status` 속성을 통해 작업의 생명주기(`active`, `paused`, `completed`, `failed`, `cancelled`)를 관리합니다. 특히 `failed` 상태일 경우, `failureCode`, `failureLabel`, `failureReason` 등을 통해 실패 원인을 기록하며, `failureActions`를 통해 복구 조치 항목을 보유합니다. (`frontend/src/page/logistics/components/focus/FocusWorkPanel.jsx`, `frontend/src/page/logistics/components/layout/MobileTaskDetail.jsx`)
*   **단계 및 위치 정보**: `currentStage`는 작업이 현재 머물고 있는 도메인 단계를 나타내며, `receiveNodeKey`는 해당 단계 내의 세부 작업 위치를 식별합니다. (`frontend/s/page/logistics/components/focus/FocusWorkPanel.jsx`)
*   **시뮬레이션 및 진행도**: `ticksInCurrentStage`와 `ticksTarget`을 사용하여 단계 내 진행률을 계산하며, 이를 통해 `progressPercent`와 같은 진행도 지표를 산출합니다. (`frontend/src/page/logistics/utils/taskHelpers.js`)
*   **식별 및 관계**: `taskId`를 고유 식별자로 사용하며, `correlationId`는 관련 이벤트들을 하나의 흐름으로 묶는 데 사용됩니다. (`frontend/src/page/logistics/services/eosSimulation.js`)

**2. Event (이벤트) 모델**
`event` 객체는 시스템 내에서 발생하는 모든 상태 변화와 액션의 기록을 담고 있는 불변 데이터입니다.
*   **식별 및 분류**: `eventId`로 고의성을 보장하며, `eventType`과 `routingKey`를 통해 이벤트의 성격(예: `order.received`, `task.recovered`)을 분류합니다. (`frontend/src/page/logistics/services/eosSimulation.js`)
*   **데이터 연결**: `aggregateId`는 해당 이벤트가 발생한 대상인 `taskId`를 참조합니다. 이를 통해 특정 작업의 이력 체인을 구성할 수 있습니다. (`frontend/src/page/logistics/components/log/logDomain.js`)
*   **메타데이터**: `timestamp`는 발생 시점을, `actor`는 행위자(예: `system`, `operator`)를 기록합니다. (`frontend/src/page/logistics/services/eosSimulation.js`)

**3. 데이터 흐름 및 상태 동기화**
*   **이력 체인(History Chain)**: 특정 `taskId`에 대해 수집된 이벤트들을 시간순으로 정렬하여 작업의 히스토리를 구성합니다. `history` 배열 내에서 `isFailureEvent` 여부에 따라 실패 이벤트를 식별하거나, `isRecoveryEvent`를 통해 복구 과정을 추적합니다. (`frontend/src/page/logistics/components/side/RightPanelContent.jsx`, `frontend/src/page/logistics/utils/index.js`)
*   **상태 업데이트**: `patchTask`를 통해 작업의 상태(예: 실패 시 상세 정보 주입, 복구 시 상태 전이)를 업데이트하며, 이는 `emitter`를 통한 이벤트 발행과 결합되어 UI에 실시간으로 반영됩니다. (`frontend/src/page/logistics/services/recoveryActions.js`)

## 시뮬레이션 엔진 및 워크플로우 제어 (Tick Loop)

시뮬레이션 엔진은 `tickLoop`를 통해 각 작업(Task)의 진행 상태를 관리하며, 작업의 현재 단계(`currentStage`)와 목표 틱 수(`ticksTarget`)를 기반으로 진행률을 계산합니다. `progressPercent` 함수는 작업의 상태가 `completed` 또는 `failed`인 경우 100%로 처리하며, 그 외에는 현재 단계에서의 진행 정도(`ticksInCurrentStage / ticksTarget`)를 백분율로 산출합니다. (`frontend/src/page/logistics/utils/taskHelpers.js`)

작업의 상태 전이는 운영자의 조치나 시스템 이벤트에 의해 발생합니다. `performBranchInject`를 통해 실패 상황을 주입하면 작업 상태가 `failed`로 변경되며, `performRecoveryAction`을 통해 복구 조치를 수행하면 작업 상태가 다시 `active`로 전환되거나 설정된 정책에 따라 종료(`cancelled`, `disposed`, `returned`)됩니다. (`frontend/src/page/logistics/services/recoveryActions.js`)

시뮬레이션의 생명주기 제어는 `useAutoMode` 훅을 통해 관리됩니다. `startTickLoop`를 호출하여 시뮬레이션을 시작하면 신규 작업(OMS/EOS) 생성이 활성화되며, `stopTickLoop`를 호출하여 정지할 수 있습니다. 특히 자동 모드(`autoMode`)가 활성화된 상태에서 신규 작업이 생성되더라도, 이미 진행 중인 기존 작업들은 `tickLoop`에 의해 계속 프로세스가 진행됩니다. (`frontend/src/page/logistics/hooks/simulation/useAutoMode.js`)

작업의 진행 상태는 `liveProgress` 값을 통해 실시간으로 반영될 수 있습니다. `withLiveProgress` 함수는 작업의 상태가 `paused`인 경우 진행률을 5%로, `failed`인 경우 95%로 보정하는 등 상태별 가중치를 적용하여 시각적인 진행도를 계산합니다. (`frontend/src/page/logistics/hooks/useLogisticsSnapshot.js`)

## 대시보드 레이아웃 및 뷰 모드 관리

`LogisticsLayout.jsx`는 화면 크기와 사용자 설정에 따라 데스크톱 뷰와 모바일 뷰를 전환하며, `useWindowState.js`를 통해 브라우저의 미디어 쿼리(`max-width: 1024px`)를 감지하여 `narrowScreen` 상태를 관리합니다.

데스크톱 환경에서는 `LogisticsDashboard.jsx`가 렌더링됩니다. 이 모드에서는 `RightPanel.jsx`를 통해 작업 상세 정보와 이력 체인을 확인할 수 있으며, `LogisticsOverlays.jsx`를 통해 설정 및 로그 오버레이가 표시됩니다. 사용자가 `useWindowState.js`의 `handleDesktopViewOpen`을 호출하면 `desktopView` 상태가 활성화되어 모바일 레이아웃에서 데스크톱 뷰로 전환될 수 있습니다.

모바일 환경에서는 `LogisticsMobileDashboard.jsx`가 렌더링됩니다. 모바일 뷰는 KPI 요약 정보와 함께 `MobileTaskDetail.jsx`를 통해 선택된 작업의 상세 정보를 표시합니다. 또한, 데스크톱 뷰로 전환하기 위한 `onDesktopViewOpen` 기능이 포함되어 있습니다.

탭 상태 관리는 `useTabState.js`를 통해 수행되며, `localStorage`에 현재 활성화된 탭(`logistics.activeTab`)을 저장하여 페이지 새로고침 시에도 상태를 유지합니다.

## 단계별 워크플로우 시각화 (Grid & Lane)

물류 프로세스의 각 단계는 `StageWorkGrid`를 기반 Co 구성되며, 이는 특정 도메인의 워크플로우를 시각화합니다. `StageWorkGrid`는 정의된 단계(`stages`)에 따라 여러 개의 `StageWorkLane`을 생성하여 수직적 흐름을 나타냅니다. `StageWorkLane`은 각 단계 내에 존재하는 세부 작업 지점인 `stageNodes`를 순차적으로 배치하며, 각 노드는 `WorkNodeCard`로 표현됩니다.

`WorkNodeCard`는 해당 지점에 적재된 작업 수량과 상태를 시각화합니다. `nodeTasks` 배열을 통해 현재 노드에 속한 작업들을 필터링하며, 각 작업의 상태(`active`, `paused`, `failed`)에 따라 카드의 색상이나 아이콘이 변경됩니다. 특히 작업의 상태가 `failed`인 경우, 카드에 실패 표시가 나타나며 `focusedFailed` 속성을 통해 포커스된 실패 작업임을 강조할 수 있습니다.

워크플로우의 흐름은 `dotState`와 `connState` 로직을 통해 시각적으로 연결됩니다. 작업의 현재 단계(`currentStage`)와 전체 워크플로우 내에서의 위치를 비교하여, 이미 완료된 단계는 `done` 상태로, 현재 진행 중인 단계는 `current` 상태로 표시됩니다. 이러한 상태값은 `RouteStrip`과 같은 컴포넌트에서 단계 간의 연결(arrow) 및 노드 상태를 결정하는 근거가 됩니다.

도메인별로 그리드 구조는 다르게 적용됩니다. 예를 들어, `OmsStageGrid`는 3열(`logistics-grid-3`), `QmsStageGrid`는 5열(`logistics-grid-5`), `InboundStageGrid`는 6열(`logistics-grid-6`) 구조를 사용하여 각 도메인의 복잡도에 맞는 레이아웃을 제공합니다. 또한 `WmsTab`과 같이 특정 조건(예: 작업 타입이 `INBOUND`)에 따라 그리드 레이아웃을 동적으로 교체하는 방식도 사용됩니다.

## 예외 처리 및 운영자 액션 (Recovery & Branch Injection)

운영자는 작업의 상태에 따라 두 가지 방식의 예외 처리 액션을 수행할 수 있습니다.

작업 상태가 `failed`인 경우, `task.failureActions`에 등록된 항목을 통해 복구 조치를 수행합니다. `performRecoveryAction` 메서드는 작업의 상태를 `active` 또는 지정된 `terminalStatus`(예: `cancelled`, `disposed`, `returned`)로 변경하며, `targetReceiveNodeKey`를 통해 작업의 위치를 재설정합니다. 또한, 복구 성공 시 `task.recovered` 이벤트를 생성하여 이력 체인에 기록합니다. (`frontend/src/page/logistics/components/focus/FocusWorkPanel.jsx`, `frontend/src/page/logistics/services/recoveryActions.js`)

작업 상태가 `active`인 경우, 현재 단계에서 발생 가능한 예외를 시뮬레이션하기 위해 `getFailureCandidatesForStage`를 통해 추출된 후보군 중 하나를 선택하여 `performBranchInject`를 호출할 수 있습니다. 이 액션은 기존 작업을 삭제(`removeTask`)한 후, `patchTask`를 통해 작업 상태를 `failed`로 전환하고 실패 원인 및 복구 가능한 액션 목록을 포함한 새로운 상태를 주입합니다. 이 과정에서 `buildInjectedFailureEvent`를 통해 실패 이벤트가 생성되어 감사 로그와 이력 체인에 기록됩니다. (`frontend/src/page/logistics/components/focus/FocusWorkPanel.jsx`, `frontend/src/page/logistics/services/recoveryActions.js`)

## 로그 분석 및 이력 체인 추적

이벤트 로그는 `logistics:event` 및 `logistics:retention:cleared` 이벤트를 통해 실시간으로 갱신되며, `LogOverlayContent.jsx`에서 필터링된 이벤트 목록을 시각화합니다. 로그의 도메인 분류는 `getLogDomain` 함수를 통해 이벤트의 `payload.stage` 또는 `routingKey`를 기준으로 OMS, WMS, TMS, AUDIT 등으로 결정됩니다 (`frontend/src/page/logistics/components/log/logDomain.js`).

로그 목록에서 특정 이벤트를 선택하면 `onEventSelect`를 통해 해당 이벤트의 `aggregateId`가 포커스 ID로 설정되어 작업(Task) 중심의 상세 이력 조회가 가능합니다 (`frontend/src/page/logistics/components/log/LogOverlayContent.jsx`). `isFocusScope`가 활성화된 경우, 로그는 현재 선택된 작업의 이력만을 표시하도록 필터링됩니다 (`frontend/src/page/logistics/components/log/LogOverlayContent.jsx`).

이력 체인(History Chain)은 작업의 상태 변화와 이벤트 흐름을 시간순으로 추적합니다. `historyRowType` 함수는 각 이벤트의 유형(실패, 복구, 완료 등)과 인덱스, 작업 상태를 종합하여 행의 스타일을 결정합니다 (`frontend/src/page/logistics/utils/index.js`). 특히 `isFailureEvent`를 통해 식별된 실패 이벤트나 `isRecoveryEvent`로 식별된 복구 이벤트는 별도의 시각적 구분(예: `fail`, `recover` 타입)을 통해 이력 체인 내에서 명확히 구분됩니다 (`frontend/src/page/logistics/utils/index.js`).

이벤트의 가독성을 위해 `historyEventLabel` 함수는 이벤트 타입과 페이로드 정보를 조합하여 '단계: 노드명' 또는 '조치 내용'과 같은 요약된 텍스트를 생성합니다 (`frontend/src/page/logistics/utils/eventHelpers.js`). 이를 통해 사용자는 복잡한 이벤트 스트림 내에서도 작업의 현재 위치와 과거 이력을 직관적으로 파악할 수 있습니다.

## 설정 관리 및 시뮬레이션 제어 (Settings & Reset)

시뮬레이션의 예외 발생률은 `simulationSettings.globalFailureRate`를 통해 관리되며, 설정 저장 시 `saveSimulationSettings`를 호출하여 `localStorage`에 영속화합니다. (`frontend/src/page/logistics/hooks/simulation/useSimulationSettings.js`, `frontend/src/page/logistics/services/simulationSettings.js`) 글로벌 예외율 변경 시 모든 단계의 설정값이 동기화되도록 구성되어 있으나, 고급 보기(`advancedOpen`) 모드에서는 각 단계별로 개별적인 예외율을 설정할 수 있는 `stageOverrides` 기능을 제공합니다. (`frontend/src/page/logistics/components/SettingsOverlay.jsx`, `frontend/src/page/logistics/hooks/simulation/useSimulationSettings.js`)

데이터 초기화는 두 가지 수준으로 제공됩니다. `handleProgressReset`은 진행 중인 데이터와 이벤트 저장소를 초기화하며, `handleFullReset`은 탭 설정(`TAB_STORAGE_KEY`)을 포함한 모든 데이터를 초기화하고 기본 탭인 `overview`로 이동합니다. (`frontend/src/page/logistics/hooks/useLogisticsReset.js`) 모든 초기화 작업 시에는 `audit.reset.performed`와 같은 감사 로그가 생성되어 운영자의 조치 사항을 기록합니다. (`frontend/src/page/logistics/hooks/useLogisticsReset.js`)
