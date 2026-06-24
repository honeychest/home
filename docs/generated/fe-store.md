# 프론트 교차영역: store

> wiki-refresh로 실제 소스를 읽고 검증함(2026-06-24). 페이지 간 관계는 `index.md` 참고.

## 한 줄 요약
물류 시뮬레이션(`fe-domain-logistics`)의 **영속·상태 계층**. IndexedDB(Dexie `LogisticsDB`)에 태스크와 이벤트를 저장하고, 변경 시 `emitter`로 UI에 알린다. 이벤트 보관 한도·감사 로그·포커스 상태를 관리한다.

## 이런 걸 물을 때 찾으면 된다 (검색 키워드)
- "물류 데이터는 어디 저장돼? IndexedDB / Dexie"
- "이벤트 보관 한도 / retention / 10000개 초과 시"
- "태스크 생성·상태 변경·조회 / taskStore"
- "감사 로그 / appendAuditEvent"
- "포커스 상태 / 선택된 태스크 / 자동 포커스"

## 핵심 개념·용어
- **LogisticsDB(Dexie)**: 브라우저 IndexedDB 래퍼. 테이블 2개: `tasks`(LogisticsTask), `events`(StoredEvent).
- **emitter 연동**: 모든 쓰기 작업이 끝나면 `@/domain/logistics/common/emitter`로 이벤트를 쏴서 UI가 반응하게 한다(`logistics:task:*`, `logistics:event`, `logistics:kpi:updated` 등).
- **retention(보관 한도)**: 이벤트가 무한히 쌓이지 않도록 `events`가 한도(10000)에 도달하면 추가 저장을 차단.
- **InMemoryQueue와의 관계**: `fe-domain-logistics`의 `InMemoryQueue`는 *런타임 pub/sub*(휘발), 이 store는 *영속 저장*(IndexedDB)이다. 둘은 별개.

## 구조 / 흐름 (확인된 코드 기준)

파일 위치: `frontend/src/store/`

### 스키마 — `db.ts`
- `class LogisticsDB extends Dexie`, `version(1).stores({...})`:
  - `tasks: 'taskId, status, currentStage, type, owner, createdAt'` (PK `taskId`)
  - `events: '++_id, eventId, aggregateId, routingKey, timestamp, correlationId'` (자동증가 `_id`)
- 싱글턴 `export const db = new LogisticsDB()`. `StoredEvent = LogisticEvent & { _id? }`.

### 이벤트 저장소 — `eventStore.ts`
- `EVENT_STORE_RETENTION_LIMIT = 10000`. `checkRetention()`: `db.events.count() >= 한도`면 `emitter.emit('logistics:retention:full')` 후 `false`(저장 차단).
- `appendEvent(event)`: 보관 한도 통과 시 `db.events.add` → `emitter.emit('logistics:event', event)`.
- `getEventsByAggregate(id)`(aggregateId로 조회, timestamp 정렬), `getAllEvents()`(timestamp 순), `getEventCount()`, `clearEventStore()`(전체 삭제 → `logistics:retention:cleared`).

### 감사 로그 — `auditStore.ts`
- `appendAuditEvent(eventType, payload, options)`: `generateUUID`로 eventId, 기본값 `aggregateId='system'`, `actor='operator'`, `correlationId`=새 UUID(없을 때), `idempotencyKey=`${aggregateId}:${eventType}:${Date.now()}``. 결과를 `eventStore.appendEvent`로 위임(같은 `events` 테이블에 적재).

### 태스크 저장소 — `taskStore.ts`
- `createTask(task)`: `db.tasks.put` → `logistics:task:created` + `logistics:kpi:updated`.
- `updateTaskStage(taskId, stage, ticksTarget)`: `currentStage/ticksInCurrentStage(0)/ticksTarget/status('active')/updatedAt` 갱신 → `task:updated` + `task:stage` + `kpi:updated`.
- `updateTaskStatus(taskId, status, failureReason?)`: status·updatedAt(+failureReason) → `task:updated` + `kpi:updated`.
- `patchTask(taskId, patch)`: 부분 수정 + updatedAt → `task:updated` + `kpi:updated`.
- 조회: `getActiveTasks()`(status active|paused), `getAllTasks()`(createdAt 순), `getTaskById(id)`. 초기화: `clearAllTasks()` → `kpi:updated`.

### 포커스 상태 — `focusStore.ts` (모듈 변수, IndexedDB 아님)
- `_focusedTaskId`(현재 선택 태스크), `_autoFocusApplied`(자동 포커스 1회 플래그). `setFocus(id)`(명시 선택), `applyAutoFocus(id)`(첫 태스크 1회만), `resetFocusState()`(둘 다 초기화). 모두 변경 시 `logistics:focus:changed` 발행. 조회 `getFocusedTaskId()`.

## 연관 도메인
- `fe-domain-logistics`(이 store가 저장하는 타입·이벤트 정의: `LogisticsTask`/`LogisticEvent`/`emitter`/`InMemoryQueue`). 상위 화면: `fe-page-logistics`. 상세 관계는 `index.md`.
