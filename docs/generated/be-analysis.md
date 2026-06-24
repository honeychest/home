# Analysis 도메인 (백엔드)

> wiki-refresh로 실제 소스를 읽고 검증함(2026-06-24). 페이지 간 관계는 `index.md` 참고.

## 한 줄 요약
사용자가 만든 **조건 트리(거래량 급증·가격 변동·델타·시간대)** 템플릿을 저장·관리하고, 그 조건을 캔들 데이터에 적용해 매칭되는 봉을 찾는 도메인이다. 1분 주기 스케줄러가 자동 탐지해 SSE로 알리고, 수동 탐색 API도 제공한다.

## 이런 걸 물을 때 찾으면 된다 (검색 키워드)
- "분석 조건 / 패턴 탐지 / 조건 트리는 어떻게 동작해?"
- "VOLUME_SPIKE PRICE_CHANGE DELTA TIME_RANGE 조건 의미"
- "AND OR NOT 그룹 연산 / GT GTE LT LTE 연산자"
- "분석 템플릿 저장 / /api/analysis/templates / search / delta"
- "실시간 시그널 자동 탐지 스케줄러 / analysis_match SSE"

## 핵심 개념·용어
- **조건 트리(ConditionTree)**: 분석 조건을 담는 JSON 구조. `groups`(그룹 리스트) → 각 그룹은 `units`(조건 단위 리스트). 템플릿의 `conditions` 컬럼에 JSON으로 저장된다.
- **그룹 간 연산(`groupOperator`)**: 그룹들을 어떻게 합치나. `AND`(모두 충족) / `OR`(하나라도). **기본값은 `OR`.**
- **그룹 내 연산(`operator`)**: 한 그룹 안 units을 어떻게 합치나. `AND`(기본) / `OR` / `NOT`(첫 유닛 결과 반전).
- **조건 단위(ConditionUnit)**: 실제 비교의 최소 단위.
  - `type`: `VOLUME_SPIKE` | `PRICE_CHANGE` | `DELTA` | `TIME_RANGE`
  - `op`(비교 연산자): `GT` | `GTE` | `LT` | `LTE` (이 4개만 `compare()`가 처리)
  - `value`: 비교 기준 수치
  - `sign`: **DELTA 전용** 부호 판정값 `POSITIVE` | `NEGATIVE` (op와 별개)
  - `startHour/startMinute/endHour/endMinute`: TIME_RANGE 시간 범위
  - `not`: true면 그 단위 결과를 최종 반전
- **델타(delta)**: 매수-매도 체결량 차이(바이낸스 집계값). 자세한 산출은 `be-binance` 참고.

## 구조 / 흐름 (확인된 코드 기준)

파일 위치: `springboot/src/main/java/com/chs/springboot/domain/analysis/`

### REST 엔드포인트 — `AnalysisTemplateController` (`@RequestMapping("/api/analysis")`)
- `GET  /api/analysis/templates` — 템플릿 목록(생성일 역순) `findAll()`
- `POST /api/analysis/templates` — 템플릿 생성 `save(req)` (201 Created)
- `PUT  /api/analysis/templates/{id}` — 이름/조건/팔레트 수정 `rename(id, req)`
- `DELETE /api/analysis/templates/{id}` — 삭제 (204)
- `GET  /api/analysis/delta?symbol&startMs&endMs&interval=1m` — 델타 시계열 조회 `getDelta(...)` (interval `1m`/`5m`에 따라 1분/5분 레포 라우팅)
- `POST /api/analysis/search` — 수동 탐색 `AnalysisSearchService.search(req)`, 조건 충족 봉의 `candle_time_ms` 목록(ASC) 반환
- `GET  /api/analysis/templates/{id}/signals?symbol&days=10` — 템플릿 조건으로 최근 `days`일 시그널 발생 날짜/캔들 `getSignalDays(...)`

### 탐지 엔진 — `AnalysisDetectionEngine.evaluate(klineData, tree)`
- 봉 인덱스 0..N을 돌며 각 봉이 조건 트리에 매칭되는지 평가해 매칭 인덱스 리스트 반환.
- `evalGroups`: `groupOperator`가 `OR`(기본)이면 `anyMatch`, `AND`면 `allMatch`.
- `evalGroup`: `NOT`이면 첫 유닛 결과 반전, `OR`이면 anyMatch, 아니면 `AND`(allMatch).
- `evalUnit`(type별):
  - **VOLUME_SPIKE**: 직전 `REF_BARS`(=20)개 평균 거래량 대비 현재 봉 거래량 비율을 `op`로 비교. idx<20이면 false.
  - **PRICE_CHANGE**: 현재 봉 `|(close-open)/open*100|`(절대 변동률%)을 비교.
  - **DELTA**: `sign`이 POSITIVE면 delta>0, NEGATIVE면 delta<0, 아니면 `value`와 `op` 비교.
  - **TIME_RANGE**: 봉 `timeMs`를 **UTC**로 변환한 분(分) 값이 start~end 범위인지(자정 넘는 범위도 처리).
- `compare(actual, expected, op)`: GT/GTE/LT/LTE만 지원(그 외 false).
- `CandleData` 레코드: `(timeMs, open, high, low, close, volume, delta)`.

### 자동 탐지 스케줄러 — `AnalysisDetectionScheduler`
- `@Scheduled(fixedDelay = 60_000)` — 1분 주기. **리더 노드(`leaderElectionService.isLeader()`)에서만** 실행.
- 대상 심볼 `SYMBOLS = [BTCUSDT, ENAUSDT]`. 각 심볼에 대해 `agg1mRepository.findTopNWithCombinedDelta(symbol, 1440)`로 최근 1440개 1분봉을 가져와(내림차순 → 오름차순 역정렬) `toCandles`로 변환.
  - 주의: 이 쿼리는 거래량을 포함하지 않아 `CandleData.volume`은 0.0으로 채워진다(델타/가격 조건 중심).
- 각 템플릿의 `conditions`를 `ObjectMapper`로 `ConditionTreeDto`로 역직렬화 → `evaluate` → 매칭 시 `{symbol, templateId, templateName, matchCount, lastMatchIdx}` 페이로드를 `signalSseService.broadcastAnalysisMatch(payload)`로 SSE 전송.
- 템플릿 1건 처리 실패가 전체를 막지 않도록 try/catch.

### 수동 탐색 — `AnalysisSearchService.search(req)`
- `Conditions`로 거래량 허용 범위(`volMin/volMax = totalVolume * (1 ∓ volTolerance/100)`)와 필터 사용 여부(useRate/useVol)를 계산.
- `timeframe` 분기: `1m`→`aggTrade1mRepository.findAllSimilarCandles`, `15m`→`aggTrade5mRepository.findAllSimilarCandles15m`, 그 외(예: `5m`)→`aggTrade5mRepository.findAllSimilarCandles`.
- 결과 각 행의 `row[0]`(candle_time_ms)을 Long으로 변환한 리스트 반환.

### 데이터 모델
- `AnalysisTemplate`(테이블 `analysis_template`): `name`, `conditions`(조건 트리 JSON), `palette`, `createdAt`, `updatedAt`.
- DTO: `TemplateRequestDto`/`TemplateResponseDto`, `ConditionTreeDto`/`ConditionGroupDto`/`ConditionUnitDto`, `AnalysisSearchRequest`(+내부 `Conditions`).

## 연관 도메인
- 데이터 출처: `be-binance`(1분/5분봉, delta, `findTopNWithCombinedDelta`, `findAllSimilarCandles`, `SignalSseService`).
- 프론트 화면: `fe-page-analysis`, `fe-domain-binance`. 상세 관계는 `index.md`.
