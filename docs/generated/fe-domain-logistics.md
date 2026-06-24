# 프론트 도메인: logistics

> wiki-refresh로 실제 소스를 읽고 검증함(2026-06-24). 페이지 간 관계는 `index.md` 참고.

## 한 줄 요약
실제 백엔드 없이 **브라우저 안에서** 물류 프로세스(주문→창고→품질→운송→정산, 자동발주, 입고)를 이벤트 기반으로 시뮬레이션하는 프론트 도메인. 인메모리 큐(`InMemoryQueue`)와 워크노드 어드밴서로 태스크를 단계별로 전이시킨다.

## 이런 걸 물을 때 찾으면 된다 (검색 키워드)
- "물류 시뮬레이션은 어떻게 동작해? OMS WMS QMS TMS"
- "태스크 파이프라인 단계 / 워크노드 / 상태 전이"
- "이벤트 큐 / routingKey / 컨슈머 구독 패턴"
- "실패 시뮬레이션 / 실패율 / 복구 정책 retry rollback"
- "자동발주 EOS / 입고 INBOUND 흐름"

## 핵심 개념·용어
- **Task(`LogisticsTask`)**: 시뮬레이션 단위(한 건의 주문/발주/입고). `type`은 `ORDER | INBOUND | EOS`. 현재 위치는 `currentStage`(단계) + `receiveNodeKey`(그 단계 안의 세부 워크노드).
- **Stage(단계)**: OMS/WMS/QMS/TMS/AFT/EOS/INBOUND 각 도메인의 큰 단계(예: `OMS_RECEIVED`, `TMS_DELIVERED`).
- **WorkNode(워크노드)**: 한 Stage 안의 세부 작업 단위(예: OMS_RECEIVED의 `raw-ingest`,`owner-match`,...). Stage별 노드 배열이 `stages.ts`에 정의됨.
- **Tick**: 시뮬레이션 진행 시간 단위. **1 tick = 100ms**, `LOGISTICS_STAGE_TICKS = 10`(노드당 기준 틱).
- **routingKey**: `{aggregate}.{verb}.{past}` 컨벤션의 이벤트 키. 큐가 이걸로 컨슈머에 라우팅.
- **InMemoryQueue**: 브라우저 메모리 안의 pub/sub 큐(실제 MQ 아님). 패턴 구독·전달 상태 추적·스냅샷 제공.

## 구조 / 흐름 (확인된 코드 기준)

파일 위치: `frontend/src/domain/logistics/`

### 타입·파이프라인 정의 — `common/events.ts`
- `TaskType = 'ORDER' | 'INBOUND' | 'EOS'`. `TaskStatus = active|paused|failed|completed|cancelled|disposed|returned`.
- 단계(Stage) 정의:
  - **OMS**: `OMS_RECEIVED → OMS_VALIDATED → OMS_WMS_REQUESTED`
  - **WMS**: `WMS_RECEIVED → WMS_ALLOCATED → WMS_PICKING → WMS_PACKED → WMS_DISPATCHED → WMS_COMPLETED`
  - **QMS**: `QMS_REQUESTED → QMS_SAMPLING → QMS_INSPECTING → QMS_JUDGED → QMS_RELEASED`
  - **TMS**: `TMS_REQUESTED → TMS_VEHICLE_ASSIGNED → TMS_LOADED → TMS_DELIVERING → TMS_DELIVERED`
  - **AFT**: `AFT_BILLING → AFT_CLOSED`
  - **EOS**(자동발주): `EOS_FORECASTED → EOS_REORDER_TRIGGERED → EOS_SUPPLIER_SELECTED → EOS_PO_ISSUED → EOS_PO_DISPATCHED → EOS_PO_CONFIRMED` → 끝에서 `INBOUND_RECEIVED`로 핸드오프
  - **INBOUND**(입고): `INBOUND_RECEIVED → INBOUND_VALIDATED → INBOUND_QC → INBOUND_ZONE_ASSIGNED → INBOUND_STORED → INBOUND_COMPLETED`
- `LogisticsTask` 주요 필드: `taskId, type, currentStage, receiveNodeKey, status, ticksInCurrentStage, ticksTarget, simulationGlobalFailureRate, simulationStageOverrides`, 그리고 실패 정보(`failureCode/Domain/Type/ResumePolicy/Actions` 등).

### 워크노드 정의 — `common/stages.ts`
- Stage별 워크노드 배열(`OMS_STAGE_WORK_NODES`, `QMS_STAGE_WORK_NODES`, `EOS_STAGE_WORK_NODES`, `TMS_STAGE_WORK_NODES`, ...). 각 노드는 `{key, label, summary, signal, output, stage, dlog, handoff, description}`.
- 노드 키 헬퍼: `getInitial...StageWorkNodeKey(stage)`(첫 노드), `getNext...StageWorkNodeKey(stage, key)`(다음 노드, 마지막이면 null), `get...StageWorkNodeLabel`.
- 틱 상수: `LOGISTICS_STAGE_TICKS=10`, 각 도메인 `*_WORK_NODE_TICKS`가 이를 참조.

### 이벤트 큐 — `common/queue.ts`
- `InMemoryQueue`(싱글턴 `logisticsQueue`):
  - `publish(routingKey, payload)`: 패턴 매칭된 컨슈머에 비동기 전달(`runDelivery`). 메시지마다 `deliveries`(컨슈머별 상태) 기록.
  - `subscribe(pattern, handler, {consumerId})`: 패턴 구독. `matchesPattern`은 `*`를 `[^.]+`로 바꾼 정규식.
  - `subscribeSnapshot(listener)`: 큐 전체 스냅샷 구독. `getQueueSnapshot`은 `messages` + 도메인별 요약(`byDomain`) + `totals` + `consumers`.
  - 상태 `QueueMessageStatus`: `unhandled|pending|processing|done|failed`(`getEnvelopeStatus`가 deliveries로 종합). `clearCompleted`/`clearAll`.

### 워크노드 어드밴서 — `common/workNodeAdvancer.ts`
- `createWorkNodeAdvancer(config, adapters)` → `advanceWorkNode(task, cb)`:
  1. `config.taskType`/`stagePrefix` 안 맞으면 false(스킵).
  2. `shouldFailAtWorkNode`(= `Math.random()*100 < failureRate` 이고 `pickFailure` 존재) → `cb.onFail` 후 종료.
  3. `publishWorkNodeEvent` → routingKey = ``${routingKeyPrefix}${stage소문자에서 prefix 제거·'_'→'.'}.${nodeKey}.done`` (예 OMS `raw-ingest` → `order.received.raw-ingest.done`), `appendEvent`로 이벤트 기록 + `emitTaskStage`.
  4. `getNextKey`로 다음 노드 계산 → `patchTask`로 `receiveNodeKey` 갱신·틱 초기화. 다음 노드 없으면 false(단계 종료).
- 어댑터 인터페이스 `WorkNodeAdapters`(`patchTask/appendEvent/emitTaskStage/getFailureRate/pickFailure`), 실 구현은 `common/workNodeAdapters.ts`(`productionWorkNodeAdapters`).

### 도메인 컨슈머 — `oms|wms|qms|tms|eos|inbound|aft/consumer.ts`
- 각 컨슈머는 `createWorkNodeAdvancer`로 `advance{Domain}WorkNode`를 만들고, `start{Domain}Consumer()`에서 자기 aggregate 패턴을 `logisticsQueue.subscribe`로 구독:

  | 도메인 | stagePrefix | taskType | routingKeyPrefix | 구독 패턴 | consumerId |
  |---|---|---|---|---|---|
  | OMS | `OMS_` | ORDER | `order.` | `order.*` | oms-domain-consumer |
  | WMS | `WMS_` | ORDER | `shipment.` | `shipment.*` | wms-domain-consumer |
  | QMS | `QMS_` | ORDER | `quality.` | `quality.*` | qms-domain-consumer |
  | TMS | `TMS_` | ORDER | `dispatch.` | `dispatch.*` | tms-domain-consumer |
  | EOS | `EOS_` | EOS | `eos.` | `eos.*` | eos-domain-consumer |
  | INBOUND | `INBOUND_` | (미지정) | `inbound.` | `inbound.*` | inbound-domain-consumer |
  | AFT | `AFT_` | (미지정) | `aft.` | `aft.*` | aft-domain-consumer |
  - INBOUND는 `taskType`을 일부러 지정하지 않는다 — EOS 태스크가 `EOS_PO_CONFIRMED → INBOUND_RECEIVED`로 전환돼도 `type`은 여전히 `'EOS'`라, taskType을 걸면 걸러지기 때문.

### 통합 실행·실패·이벤트
- `common/workNodeRegistry.ts`: `advanceWorkNode(task, cb)`가 OMS→WMS→QMS→TMS→EOS→INBOUND→AFT 순으로 각 도메인 어드밴서를 시도(첫 매칭이 처리). `getInitialKeyForStage`/`getWorkNodeTicksForStage`/`getWorkNodeLabel`.
- `common/failures.ts`: `FAILURE_CATALOG: Record<TaskStage, FailureDefinition[]>`. `FailureType`(business/system/external/capacity/data), `ResumePolicy`(retry_current_stage/rollback_previous_stage/manual_review/cancel_only), `FailureAction`(id/label/nextStage?/nextReceiveNodeKey?). 실패 시 정의된 액션으로 단계 롤백·재시도·수동검토·취소.
- `common/emitter.ts`: `mitt` 기반 UI 이벤트 버스(`logistics:event`, `task:created/updated/stage`, `focus:changed`, `health:changed`, `retention:*`, `kpi:updated`).

## 연관 도메인
- 순수 프론트 시뮬레이션(백엔드 도메인 의존 없음). 상위 화면: `fe-page-logistics`(OMS/TMS 시각화). 상세 관계는 `index.md`.
