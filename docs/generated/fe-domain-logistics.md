# 프론트 도메인: logistics

> 이 문서는 로컬 LLM(gemma-4-26b-a4b-it-mlx)이 소스 코드를 근거로 자동 생성했다. 검증 전 초안이다.

## 목차
- 도메인 개요 및 핵심 개념
- 태스크 파이프라인 및 스테이지 정의
- 워크 노드(Work Node) 구조 및 상태 전이 메커니즘
- 이벤트 기반 메시지 큐(Queue) 아키텍처
- 도메인별 컨슈머 및 이벤트 발행 로직
- 실패 정의(Failure Definition) 및 복구 정책
- 시뮬레이션 및 워크 노드 어드밴서(Advancer) 엔진

## 도메인 개요 및 핵심 개념

이 도메인은 물류 프로세스의 각 단계(Stage)와 세부 작업 단위(Work Node)를 관리하며, 태스크의 상태 변화에 따른 이벤트 발행 및 워크 노드 전이(Advancement)를 핵심으로 합니다.

**1. 태스크 파이프라인과 도메인 구조**
물류 흐름은 `ORDER` 타입의 메인 파이프라인과 `EOS`(자동발주) 및 `INBOUND`(입고)로 구성된 보조 파이프라인으로 구분됩니다. `ORDER` 타입은 OMS, WMS, QMS, TMS, AFT 단계를 거치며 `PIPELINE_STAGES`에 정의된 순서로 진행됩니다. 반면, `EOS` 타입은 예측부터 입고 완료까지의 흐름을 가지며, 최종적으로 `INBOUND_RECEIVED` 단계로 핸드오프되어 입고 흐름으로 이어집니다. (`frontend/src/domain/logistics/common/events.ts`, `frontend/src/domain/logistics/common/stages.ts`)

**2. 워크 노드(Work Node)와 단계(Stage) 관리**
각 `TaskStage`는 여러 개의 세부적인 `WorkNode`로 구성됩니다. 워크 노드는 특정 단계 내에서 수행되는 구체적인 작업 단위를 의미하며, `createWorkNodeAdvancer`를 통해 각 도메인별(OMS, WMS, QMS 등)로 독립적인 전이 로직이 구현됩니다. (`frontend/src/domain/logistics/common/workNodeAdvancer.ts`)
- **전이 로직**: `getNextKey` 메서드를 통해 현재 노드에서 다음 노드로의 전환을 관리하며, 성공 시 `patchTask`를 통해 태스크의 `receiveNodeKey`와 `ticks` 정보를 업데이트합니다. (`frontend/src/domain/logistics/common/workNodeAdvancer.ts`)
- **시간 관리**: 각 단계와 노드는 `workNodeTicks`를 기준으로 진행 시간을 관리하며, 이는 시뮬레이션 및 작업 진행 상태를 나타내는 지표로 사용됩니다. (`frontend/src/domain/logistics/common/stages.ts`)

**3. 이벤트 기반 통신 및 큐(Queue) 메커니즘**
도메인 간의 상태 변화와 작업 완료는 `InMemoryQueue`를 통한 이벤트 발행 방식으로 이루어집니다.
- **라우팅 키(Routing Key)**: `{aggregate}.{verb}.{past-tense}` 컨벤션을 따르며, 각 도메인은 특정 패턴(예: `order.*`, `shipment.*`, `quality.*`)을 구독하는 컨슈머를 통해 메시지를 수신합니다. (`frontend/src/domain/logistics/common/events.ts`, `frontend/src/domain/logistics/common/queue.ts`)
- **이벤트 발행**: 워크 노드 작업 완료 시 `publishWorkNodeEvent`를 통해 특정 라우팅 키를 가진 이벤트가 발행되며, 이는 태스크의 상태 변화와 동시에 도메인 간 데이터 전달 역할을 수행합니다. (`frontend/src/domain/logistics/common/workNodeAdvancer.ts`)

**4. 실패 처리 및 복구 정책**
각 워크 노드에서는 `FailureDefinition`에 정의된 시나리오에 따라 실패 가능성을 관리합니다. 특정 노드에서 오류가 발생할 경우, `FailureAction`에 정의된 `nextStage` 또는 `nextReceiveNodeKey`를 통해 재시도, 단계 롤백, 혹은 수동 검토와 같은 복구 정책이 적용됩니다. (`frontend/src/domain/logistics/common/failures.ts`)

## 태스크 파이프라인 및 스테이지 정의

태스크의 유형(TaskType)에 따라 수행되는 파이프라인과 각 단계별 정의는 다음과 같습니다.

**1. 태스크 유형별 파이프라인 구성**
- **ORDER (주문)**: `OMS_RECEIVED` → `OMS_VALIDATED` → `OMS_WMS_REQUESTED` → `WMS_RECEIVED` → `WMS_ALLOCATED` → `WMS_PICKING` → `WMS_PACKED` → `QMS_REQUESTED` → `QMS_SAMPLING` → `QMS_INSPECTING` → `QMS_JUDGED` → `QMS_RELEASED` → `TMS_REQUESTED` → `sTMS_VEHICLE_ASSIGNED` → `TMS_LOADED` → `TMS_DELIVERING` → `TMS_DELIVERED` → `AFT_BILLING` → `AFT_CLOSED` 순으로 진행됩니다. (`frontend/src/domain/logistics/common/events.ts`)
- **INBOUND (입고)**: `INBOUND_RECEIVED` → `INBOUND_VALIDATED` → `INBOUND_QC` → `INBOUND_ZONE_ASSIGNED` → `INBOUND_STORED` → `INBOUND_COMPLETED` 순으로 진행됩니다. (`frontend/src/domain/logistics/common/events.ts`)
- **EOS (자동발주)**: `EOS_FORECASTED` → `EOS_REORDER_TRIGGERED` → `EOS_SUPPLIER_SELECTED` → `EOS_PO_ISSUED` → `EOS_PO_DISPATCHED` → `EOS_PO_CONFIRMED` → `INBOUND_RECEIVED` → `INBOUND_VALIDATED` → `INBOUND_QC` → `INBOUND_ZONE_ASSIGNED` → `INBOUND_STORED` → `INBOUND_COMPLETED` 순으로 진행됩니다. (`frontend/src/domain/logistics/common/events.ts`)

**2. 주요 스테이지 정의 및 역할**
- **OMS (주문 관리)**: 주문 접수(`OMS_RECEIVED`), 검증(`OMS_VALIDated`), WMS 전송(`OMS_WMS_REQUESTED`) 단계를 포함합니다. (`frontend/src/domain/logistics/common/events.ts`)
- **WMS (창고 관리)**: 창고 작업 접수(`WMS_RECEIVED`), 재고 할당(`WMS_ALLOCATED`), 피킹(`WMS_PICKING`), 패킹(`WMS_PACKED`), 출하(`WMS_DISPATCHED`), 출하 완료(`WMS_COMPLETED`) 단계를 포함합니다. (`frontend/src/domain/logistics/common/events.ts`)
- **QMS (품질 관리)**: 검사 요청(`QMS_REQUESTED`), 샘플 추출(`QMS_SAMPLING`), 검사 진행(`QMS_INSPECTING`), 판정(`QMS_JUDGED`), 출고 승인(`QMS_RELEASED`) 단계를 포함합니다. (`frontend/src/domain/logistics/common/events.ts`)
- **TMS (운송 관리)**: 배차 요청(`TMS_REQUESTED`), 차량 배정(`TMS_VEHICLE_ASSIGNED`), 상차(`TMS_LOADED`), 운송(`TMS_DELIVERING`), 인도(`TMS_DELIVERED`) 단계를 포함합니다. (`frontend/src/domain/logistics/common/events.ts`)
- **EOS (자동발주)**: 수요예측(`EOS_FORECASTED`), 발주점(`EOS_REORDER_TRIGGERED`), 공급사 선정(`EOS_SUPPLIER_SELECTED`), 발주서 발행(`EOS_PO_ISSUED`), 공급사 송신(`EOS_PO_DISPATCHED`), 수신확인(`EOS_PO_CONFIRMED`) 단계를 포함합니다. (`frontend/src/domain/logistics/common/events.ts`)
- **INBOUND (입고)**: 입고 등록(`INBOUND_RECEIVED`), 유효성 검증(`INBOUND_VALIDATED`), IQC(`INBOUND_QC`), Zone 배정(`INBOUND_ZONE_ASSIGNED`), 재고 반영(`INBOUND_STORED`), 완료(`INBOUND_COMPLETED`) 단계를 포함합니다. (`frontend/src/domain/logistics/common/events.ts`)
- **AFT (정산 및 종결)**: 배송 후 정산 처리(`AFT_BILLING`), 주문 최종 종결(`AFT_CLOSED`) 단계를 포함합니다. (`frontend/src/domain/logistics/common/events.ts`)

## 워크 노드(Work Node) 구조 및 상태 전이 메커니즘

워크 노드는 각 도메인 단계(Stage) 내에서 수행되는 세부적인 작업 단위를 의미하며, `LogisticsTask`의 `receiveNodeKey`를 통해 현재 진행 중인 노드를 식별합니다. 모든 워크 노드는 `createWorkNodeAdvancer`를 통해 생성된 어드밴서(Advancer)에 의해 제어되며, 각 단계의 시작 노드는 `getInitial...StageWorkNodeKey` 메서드를 통해 결정됩니다.

상태 전이 과정은 다음과 같은 메커니즘으로 동작합니다:

1.  **실행 및 실패 판정**: `advanceWorkNode` 함수가 호출되면, 먼저 `shouldFailAtWorkNode`를 통해 시뮬레이션 설정(`simulationGlobalFailureRate`, `simulationStageOverrides`) 및 `pickFailure`를 기반으로 해당 노드에서의 실패 여부를 결정합니다. 만약 실패가 발생하면 `cb.onFail`이 호출되며 전이가 중단됩니다. (`frontend/src/domain/logistics/common/workNodeAdvancer.ts`)
2.  **이벤트 발행**: 실패하지 않은 경우, `publishWorkNodeEvent`가 호출되어 현재 노드의 작업 완료를 알리는 이벤트를 생성합니다. 이때 `routingKey`는 설정된 `routingKeyPrefix`, 단계명, 그리고 `safeKey`(현재 노드 키)를 조합하여 생성되며(예: `order.received`), `appendEvent`를 통해 시스템에 기록됩니다. (`frontend/src/domain/logistics/common/workNodeAdvancer.ts`)
3.  **노드 전이**: 이벤트 발행 후, `getNextKey`를 호출하여 다음 노드의 키를 계산합니다. 성공적으로 다음 키가 결정되면 `patchTask`를 통해 해당 태스크의 `receiveNodeKey`를 새로운 키로 업데이트하고, `ticksInCurrentStage`를 0으로 초기화하며 `ticksTarget`을 설정하여 새로운 노드로의 전이를 완료합니다. (`frontend/src/domain/logistics/common/workNodeAdvancer.ts`)

이러한 구조를 통해 태스크는 `receiveNodeKey`가 가리키는 노드에서 작업을 수행하고, 완료 시 다음 노드로 순차적으로 이동하거나 실패 시 정의된 정책에 따라 처리됩니다.

## 이벤트 기반 메시지 큐(Queue) 아키텍처

이 시스템은 `InMemoryQueue` 클래스를 기반한 이벤트 기반 메시지 큐 아키텍처를 사용합니다. `logisticsQueue` 인스턴스는 모든 도메인 간의 비동기 통신을 담당하며, `routingKey` 패턴 매칭을 통해 메시지를 적절한 컨슈머에게 전달합니다.

**1. 메시지 발행 및 라우팅 메커니즘**
`publish` 메서드를 통해 생성된 `QueueEnvelope`는 지정된 `routingKey`를 기반으로 구독 중인 컨슈머들을 식별합니다. `matchesPattern` 함수는 정규식을 사용하여 와일드카드(`*`)가 포함된 패턴(예: `aft.*`, `order.*`)과 실제 `routingKey` 간의 일치 여부를 판단합니다. 메시지가 발행되면 매칭된 모든 컨슈머에게 `runDelivery`를 통해 비동기적으로 핸들러가 실행됩니다.
- 근거: `frontend/src/domain/logistics/common/queue.ts`

**2. 컨슈머 구독 및 생명주기 관리**
각 도메인(OMS, WMS, QMS, TMS, EOS, INBOUND, AFT)은 전용 컨슈머를 통해 특정 패턴의 메시지를 구독합니다. 예를 들어, `aft.consumer.ts`는 `logisticsQueue.subscribe('aft.*', ...)`를 호출하여 관련 메시지를 수신합니다. `subscribe` 시 설정된 `consumerId`는 큐 내에서 메시지 전달 상태를 추적하는 데 사용되며, 구독 해제 시에는 반환된 함수를 호출하여 컨슈머를 제거할 수 있습니다.
- 근거: `frontend/src/domain/logistics/aft/consumer.ts`, `frontend/src/domain/logistics/common/queue.ts`

**3. 메시지 전달 상태 및 스냅샷**
메시지의 생명주기는 `QueueMessageStatus`(`unhandled`, `pending`, `processing`, `done`, `failed`)로 관리됩니다. 하나의 메시지는 여러 컨슈머에게 전달될 수 있으며, 각 컨슈머별 진행 상태는 `QueueDeliverySnapshot`에 기록됩니다. `getEnvelopeStatus` 함수는 모든 컨슈머의 완료 여부를 종합하여 메시지의 최종 상태를 결정합니다. 시스템은 `emitSnapshot`을 통해 현재 큐의 전체 상태(메시지 목록, 도메인별 요약, 컨슈mer 정보 등)를 `QueueSnapshot` 형태로 유지하고 구독자들에게 알립니다.
- 근거: `frontend/src/domain/logistics/common/queue.ts`

**4. 도메인별 라우팅 키 컨벤션**
각 단계의 이벤트는 `{aggregate}.{verb}.{past-tense}` 형식의 규칙을 따르는 `routingKey`를 생성하여 발행됩니다. 이는 도메인 간 명확한 책임 분리와 이벤트 흐름 추적을 가능하게 합니다.
- 근거: `frontend/src/domain/logistics/common/events.ts`

## 도메인별 컨슈머 및 이벤트 발행 로직

각 도메인별 컨슈머는 `logisticsQueue`를 통해 특정 패턴의 메시지를 구독하며, 작업 단계(Work Node)가 진행됨에 따라 `createWorkNodeAdvancer`를 통해 이벤트를 발행하고 태스크 상태를 업데이트합니다.

**1. 도메인별 컨슈머 등록 및 메시지 수신**
각 도메인은 `start[Domain]Consumer` 함수를 통해 고유한 패턴의 메시지를 구독합니다.
* **OMS**: `order.*` 패턴을 구독하며, `taskType: 'ORDER'`인 경우에만 동작합니다. (`frontend/src/domain/logistics/oms/consumer.ts`)
* **WMS**: `shipment.*` 패턴을 구독하며, `taskType: 'ORDER'`인 경우에만 동작합니다. (`frontend/src/domain/logistics/wms/consumer.ts`)
* **QMS**: `quality.*` 패턴을 구독하며, `taskType: 'ORDER'`인 경우에만 동작합니다. (`frontend/src/domain/logistics/qms/consumer.ts`)
* **TMS**: `dispatch.*` 패턴을 구독하며, `taskType: 'ORDER'`인 경우에만 동작합니다. (`frontend/src/domain/logistics/tms/consumer.ts`)
* **EOS**: `eos.*` 패턴을 구독하며, `taskType: 'EOS'`인 경우에만 동작합니다. (`frontend/src/domain/logistics/eos/consumer.ts`)
* **INBOUND**: `inbound.*` 패턴을 구독합니다. 별도의 `taskType` 지정이 없어 EOS 태스크가 INBOUND 단계로 전환될 때도 동작합니다. (`frontend/src/domain/logistics/inbound/consumer.ts`)
* **AFT**: `aft.*` 패턴을 구독합니다. (`frontend/src/domain/logistics/aft/consumer.ts`)

**2. 이벤트 발행 및 태스크 업데이트 로직**
`createWorkNodeAdvancer`로 생성된 각 도메인별 `advance[Domain]WorkNode` 함수는 다음과 같은 흐름으로 이벤트를 발행하고 상태를 관리합니다. (`frontend/src/domain/logistics/common/workNodeAdvancer.ts`)

* **실패 처리**: `shouldFailAtWorkNode` 함수를 통해 시뮬레이션 설정(`globalFailureRate`, `stageOverrides`)에 따른 실패 여부를 먼저 판단합니다. 실패 시 `cb.onFail`을 호출하고 프로세스를 중단합니다. (`frontend/src/domain/logistics/common/workNodeAdvancer.ts`)
* **이벤트 발행 (`publishWorkNodeEvent`)**: 실패하지 않은 경우, `buildPayload`를 통해 구성된 데이터와 현재 단계 정보를 포함한 이벤트를 생성합니다. 이때 `routingKey`는 설정된 `routingKeyPrefix`와 단계명, 그리고 `safeKey`(현재 노드 키)를 조합하여 생성되며(예: `order.received`), `appendEvent`를 통해 시스템에 기록됩니다. (`frontend/src/domain/logistics/common/workNodeAdvancer.ts`)
* **상태 전이 (`patchTask`)**: 이벤트 발행 직후, `getNextKey`를 통해 다음 노드 키를 계산합니다. 이후 `patchTask`를 호출하여 해당 태스크의 `receiveNodeKey`를 새로운 키로 업데이트하고, `ticksInCurrentStage`를 0으로 초기화합니다. (`frontend/src/domain/logistics/common/workNodeAdvancer.ts`)

## 실패 정의(Failure Definition) 및 복구 정책

각 단계별로 발생할 수 있는 실패 상황은 `FAILURE_CATALOG`에 정의된 `FailureDefinition`을 따르며, 이는 각 도메인의 업무 규칙과 단계별 특성을 반영합니다.

**실패 유형 및 분류**
실패는 `FailureType`에 따라 `business`, `system`, `external`, `capacity`, `data`로 분류됩니다 (`frontend/src/domain/logistics/common/failures.ts`). 또한, 각 실패는 해당 단계의 `receiveNodeKey`와 매칭되어 특정 작업 노드에서 발생한 상황을 구체화합니다 (`frontend/src/domain/logistics/common/failures.ts`).

**복구 정책 (Resume Policy)**
실패 발생 시 적용되는 복구 전략은 `ResumePolicy`로 정의되며, 다음과 같은 네 가지 유형이 있습니다 (`frontend/src/domain/logistics/common/failures.ts`):
* **retry_current_stage**: 현재 단계에서 작업을 재시도합니다. (예: `OMS_RECEIVED` 단계의 `OMS_RAW_PAYLOAD_MISSING`)
* **rollback_previous_stage**: 이전 단계로 되돌아갑니다. (예: `TMS_VEHICLE_ASSIGNED` 단계의 `TMS_ASSIGNMENT_CONFLICT`)
* **manual_review**: 운영자의 수동 검토를 통해 후속 조치를 결정합니다. (예: `OMS_VALIDATED` 단계의 `OMS_CONTRACT_MISMATCH`)
* **cancel_only**: 작업을 취소하고 종료합니다. (예: `INBOUND_QC` 단계의 `IN^OUND_QC_DEFECT_REJECT`)

**실패 조치 (Failure Action)**
각 실패 정의는 상황에 맞는 `FailureAction` 목록을 포함합니다. 조치 결과로 특정 단계로 이동하거나(`nextStage`), 새로운 작업 노드로 전환(`nextReceiveNodeKey`)될 수 있습니다 있습니다 (`frontend/src/domain/logistics/common/failures.ts`). 예를 들어, `OMS_VALIDATED` 단계의 `OMS_DESTINATION_OUT_OF_ZONE` 실패 시 `confirm_address` 조치를 통해 주소를 재확인하는 식의 흐름이 가능합니다.

## 시뮬레이션 및 워크 노드 어드밴서(Advancer) 엔진

시뮬레이션 및 워크 노드 어드밴서(Advancer) 엔진은 `LogisticsTask`의 상태를 단계별로 전이시키며, 설정된 실패율에 따른 시뮬레이션과 이벤트 발행을 담당합니다.

**1. 워크 노드 전이 로직 (Work Node Advancing)**
`createWorkNodeAdvancer` 함수는 특정 도메인(예: OMS, WMS 등)의 작업 단계를 관리하는 함수를 생성합니다. `advanceWorkNode` 호출 시, 현재 태스크의 `currentStage`가 설정된 `stagePrefix`와 일치하는지 확인합니다. (`frontend/src/domain/logistics/common/workNodeAdvancer.ts`)

* **실패 시뮬레이션:** `shouldFailAtWorkNode` 함수는 태스크에 설정된 `simulationGlobalFailureRate`와 `simulationStageOverrides`를 기반으로 실패 여부를 결정합니다. 만약 계산된 확률이 임계치보다 낮으면 `pickFailure`를 통해 해당 단계의 실패 정의(`FailureDefinition`)를 선택하고, `onFail` 콜백을 실행합니다. (`frontend/src/domain/logistics/common/workNodeAdvancer.ts`)
* **이벤트 발행 및 상태 업데이트:** 실패하지 않은 경우, `publishWorkNodeEvent`를 통해 작업 완료 이벤트를 발행합니다. 이벤트는 `routingKeyPrefix`와 현재 노드 키를 조합하여 생성되며, `appendEvent`를 통해 시스템에 기록됩니다. 이후 `getNextKey`를 호출하여 다음 작업 노드 키(`receiveNodeKey`)를 결정하고, `patchTask`를 통해 태스크의 상태(다음 키, 틱 초기화 등)를 업데이트합니다. (`frontend/src/domain/logistics/common/workNodeAdvancer.ts`)

**2. 도메인별 어댑터 및 설정 (Domain-specific Configuration)**
각 도메인 컨슈머는 `WorkNodeDomainConfig`를 통해 고유한 동작 규칙을 정의합니다. (`frontend/src/domain/logistics/oms/consumer.ts`, `frontend/src/domain/logistics/wms/consumer.ts` 등)

* **키 관리:** `getInitialKey`와 `getNextKey`를 사용하여 각 단계의 시작 키와 다음 노드 키를 관리합니다. (`frontend/src/domain/logistics/common/stages.ts`)
* **데이터 페이로드:** `buildPayload`를 통해 각 도메인 특성에 맞는 데이터(예: WMS의 `zoneCode`, QMS의 `boxId` 등)를 이벤트 페이로드에 포함합니다. (`frontend/src/domain/logistics/common/workNodeAdvancer.ts`)
* **시간 제어:** `workNodeTicks` 설정을 통해 각 작업 노드에서 소요되는 기준 시간(Tick)을 정의합니다. (`frontend/src/domain/logistics/common/workNodeAdvancer.ts`)

**3. 통합 실행 흐름 (Registry)**
`advanceWorkNode` 함수는 `workNodeRegistry`를 통해 모든 도메인 컨슈머의 어드밴서를 순차적으로 호출합니다. 각 도메인의 `taskType`과 `stagePrefix`가 태스크 정보와 일치하는 어드밴서가 실행되어 적절한 전이 로직을 수행합니다. (`frontend/src/domain/logistics/common/workNodeRegistry.ts`)
