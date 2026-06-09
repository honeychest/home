# 프론트 교차영역: store

> 이 문서는 로컬 LLM(gemma-4-26b-a4b-it-mlx)이 소스 코드를 근거로 자동 생성했다. 검증 전 초안이다.

## 목차
- 개요 및 데이터베이스 스키마 정의
- 이벤트 저장소(EventStore) 관리 및 보관 정책
- 감사 로그(Audit Log) 생성 흐름
- 태스크(Task) 생명주기 및 상태 관리
- 태스크 데이터 조회 및 필터링
- 포커스(Focus) 상태 제어 메커니즘
- 데이터 초기화 및 클리어 프로세스

## 개요 및 데이터베이스 스키마 정의

본 시스템의 스토어 도메인은 IndexedDB를 기반으로 하는 `LogisticsDB`를 통해 데이터의 영속성을 관리하며, `frontend/src/store/db.ts`에서 정의된 스키마를 사용한다.

### 데이터베이스 스키마 정의
`frontend/src/store/db.ts`에 정의된 `LogisticsDB`는 두 개의 주요 테이블을 포함한다.

1.  **tasks 테이블**: `LogisticsTask` 객체를 저장하며, 다음과 같은 인덱스를 가진다.
    *   인덱스 필드: `taskId`, `status`, `currentStage`, `type`, `owner`, `createdAt`
    *   주요 기능: `frontend/src/store/taskStore.ts`를 통해 태스크의 생성(`createTask`), 상태 업데이트(`updateTaskStatus`), 단계 변경(`updateTaskStage`), 부분 수정(`patchTask`) 및 조회(`getActiveTasks`, `getAllTasks`, `getTaskById`)가 수행된다.

2.  **events 테이블**: `LogisticEvent`와 `_id`(자동 증가 번호)를 포함하는 `StoredEvent` 객체를 저장한다.
    *   인덱스 필드: `++_id`, `eventId`, `aggregateId`, `routingKey`, `timestamp`, `correlationId`
    *   주요 기능: `frontend/src/store/eventStore.ts`를 통해 이벤트의 추가(`appendEvent`), 특정 집계 ID별 조회(`getEventsByAggregate`), 전체 목록 조회(`getAllEvents`), 개수 확인(`getEventCount`) 및 저장소 초기화(`clearEventStore`)가 수행된다. `frontend/src/store/auditStore.ts`의 `appendAuditEvent`를 통해 생성된 이벤트 또한 이 테이블에 저장된다.

## 이벤트 저장소(EventStore) 관리 및 보관 정책

이벤트 저장소는 `frontend/src/store/db.ts`에 정의된 IndexedDB를 기반으로 하며, `LogisticsDB` 클래스의 `events` 테이블을 통해 이벤트를 관리한다.

이벤트 저장소의 보관 정책은 다음과 같다:

1. **보관 한도 및 차단 정책**: `frontend/src/store/eventStore.ts`에 정의된 `EVENT_STORE_RETENTION_LIMIT` 값(10,000개)을 기준으로 보관 정책이 작동한다. `appendEvent` 호출 시 `checkRetention` 함수를 통해 현재 이벤트 개수를 확인하며, 저장된 이벤트 수가 한도에 도달하면 `logistics:retention:full` 이벤트를 발생시키고 추가 저장을 차단한다.
2. **이벤트 저장 흐름**: `frontend/src/store/eventStore.ts`의 `appendEvent` 함수는 `checkRetention`을 통해 저장 가능 여부를 확인한 후, `db.events.add`를 통해 이벤트를 IndexedDB에 저장하고 `logistics:event` 이벤트를 발생시킨다.
3. **데이터 조회 및 초기화**: 
    - 특정 `aggregateId`를 기준으로 이벤트를 조회하거나(`getEventsByAggregate`), 전체 이벤트를 시간순으로 정렬하여 가져올 수 있다(`getAllEvents`).
    - 운영자 초기화 등을 위해 `frontend/src/store/eventStore.ts`의 `clearEventStore` 함수를 호출하면 `db.events.clear()`를 통해 모든 이벤트가 삭제되며, `logistics:retention:cleared` 이벤트가 발생한다.

## 감사 로그(Audit Log) 생성 흐름

`frontend/src/store/auditStore.ts`의 `appendAuditEvent` 함수가 호출되면 `generateUUID`를 통해 고유한 ID를 생성하고, 전달된 `eventType`, `payload`, `options` 값을 바탕으로 감사 이벤트를 구성한다. 이때 `options.aggregateId`가 제공되지 않으면 `'system'`으로, `options.actor`가 제공되지 않으면 `'operator'`로 설정된다. 또한 `options.correlationId`가 없으면 새로운 UUID를 생성하며, `idempotencyKey`는 `${options.aggregateId ?? 'system'}:${eventType}:${Date.now()}` 형식으로 생성된다.

구성된 감사 이벤트는 `frontend/src/store/eventStore.ts`의 `appendEvent` 함수로 전달된다. `appendEvent`는 먼저 `checkRetention` 함수를 호출하여 현재 `db.events`의 데이터 개수가 `EVENT_STORE_RETENTION_LIMIT`(10,000개)에 도달했는지 확인한다. 만약 보관 한도에 도달하여 `checkRetention`이 `false`를 반환하면 이벤트 저장은 중단된다.

보관 한도 문제가 없을 경우, 이벤트는 `db.events.add`를 통해 IndexedDB에 저장된다. 저장이 완료되면 `emitter.emit('logistics:event', event)`를 통해 시스템 내에 이벤트 발생이 전파된다.

## 태스크(Task) 생명주기 및 상태 관리

태스크의 생성 및 상태 변화는 `frontend/src/store/taskStore.ts`를 통해 관리된다.

새로운 태스크는 `createTask` 메서드를 호출하여 `frontend/src/store/db.ts`의 `tasks` 테이블에 저장되며, 생성 시 `logistics:task:created` 이벤트와 `logistics:kpi:updated` 이벤트가 각각 발생한다.

태스크의 상태 및 단계 변경은 다음과 같은 메서드를 통해 수행된다:
*   **단계 업데이트**: `updateTaskStage`를 호출하면 특정 태스크의 `currentStage`, `ticksInCurrentStage`(0으로 초기화), `ticksTarget`, `status`('active'), `updatedAt`이 업데이트된다. 이후 `logistics:task:updated`, `logistics:task:stage`, `logistics:kpi:updated` 이벤트가 순차적으로 발생한다.
*   **상태 업데이트**: `updateTaskStatus`를 호출하여 태스크의 `status`와 `updatedAt`을 변경할 수 있으며, 실패 사유(`failureReason`)가 제공될 경우 해당 필드도 함께 업데이트된다. 작업 완료 후에는 `logistics:task:updated` 및 `logistics:kpi:updated` 이벤트가 발생한다.
*   **부분 업데이트**: `patchTask`를 통해 특정 필드에 대한 부분적인 수정을 수행하며, `updatedAt`은 현재 시간으로 갱신된다. 업데이트 시 `logistics:task:updated`와 `logistics:kpi:updated` 이벤트가 발생한다.

조회 및 초기화 기능은 다음과 같다:
*   **태스크 조회**: `getActiveTasks`를 통해 상태가 'active' 또는 'paused'인 태스크 목록을 가져올 수 있으며, `getAllTasks`를 통해 생성일(`createdAt`) 순으로 정렬된 전체 태스크 목록을 가져올 수 있다. 특정 ID를 가진 태스크는 `getTaskById`로 조회 가능하다.
*   **데이터 초기화**: `clearAllTasks`를 호출하면 `tasks` 테이블의 모든 데이터가 삭제되며, `logistics:kpi:updated` 이벤트가 발생한다.

## 태스크 데이터 조회 및 필터링

`frontend/src/store/db.ts`에 정의된 `tasks` 테이블을 통해 태스크 데이터를 관리하며, 주요 조회 기능은 다음과 같다.

*   **전체 태스크 조회**: `frontend/sct/store/taskStore.ts`의 `getAllTasks` 메서드를 호출하여 생성일(`createdAt`) 순으로 정렬된 모든 태스크 목록을 가져올 수 있다.
*   **특정 ID로 조회**: `frontend/src/store/taskStore.ts`의 `getTaskById` 메서드를 통해 특정 `taskId`에 해당하는 태스크 정보를 조회한다.
*   **활성 상태 태스크 필터링**: `frontend/src/store/taskStore.ts`의 `getActiveTasks` 메서드를 호출하면 상태(`status`)가 'active' 또는 'paused'인 태스크들만 필터링하여 가져온다.

## 포커스(Focus) 상태 제어 메커니즘

포커스 상태는 `frontend/src/store/focusStore.ts`에서 관리되며, 현재 선택된 작업 ID를 나타하는 `_focusedTaskId`와 자동 포커스 적용 여부를 제어하는 `_autoFocusApplied` 변수를 통해 유지된다.

사용자가 카드를 클릭하거나 목록 행을 선택하는 등 명시적인 동작이 발생할 경우 `setFocus` 함수를 호출하여 `_focusedTaskId`를 업데이트하고, `logistics:focus:changed` 이벤트를 발생시킨다.

Auto 모드에서 첫 작업 생성 시 1회에 한해 포커스를 적용하기 위해 `applyAutoFocus` 함수를 사용한다. 이 함수는 내부적으로 `_autoFocusApplied` 플래그를 확인하여 이미 적용된 경우 동작을 제한하며, 적용 시 `_focusedTaskId`를 설정하고 `logistics:focus:changed` 이벤트를 발생시킨다.

포커스 상태를 초기화할 때는 `resetFocusState` 함수를 호출하여 `_autoFocusApplied`를 `false`로, `_focusedTaskId`를 `null`로 설정하며, `logistics:focus:changed` 이벤트를 발생시킨다. 현재 포커스된 작업 ID는 `getFocusedTaskId`를 통해 조회할 수 있다.

## 데이터 초기화 및 클리어 프로세스

데이터 초기화 및 클리어 프로세스는 각 스토어의 목적에 따라 다음과 같이 수행된다.

*   **이벤트 데이터 초기화**: `frontend/src/store/eventStore.ts`의 `clearEventStore` 메서드를 호출하여 IndexedDB 내의 모든 이벤트를 삭제한다. 작업 완료 후 `logistics:retention:cleared` 이벤트를 발생시킨다.
*   **태스크 데이터 초기화**: `frontend/src/store/taskStore.ts`의 `clearAllTasks` 메서드를 호출하여 모든 태스크 데이터를 삭제한다. 작업 완료 후 `logistics:kpi:updated` 이벤트를 발생시킨다.
*   **포커스 상태 초기화**: `frontend/src/store/focusStore.ts`의 `resetFocusState` 메서드를 호출하여 자동 포커스 적용 여부(`_autoFocusApplied`)를 `false`로, 선택된 태스크 ID(`_focusedTaskId`)를 `null`로 초기화한다. 이후 `logistics:focus:changed` 이벤트를 발생시킨다.
