# Analysis 도메인

> 이 문서는 로컬 LLM(gemma-4-26b-a4b-it-mlx)이 소스 코드를 근거로 자동 생성했다. 검증 전 초안이다.

## 목차
- 개요
- 데이터 모델 및 엔티티 구조
- 조건 트리(Condition Tree) 스펙 및 DTO 정의
- 탐지 엔진(Detection Engine) 로직
- 실시간 스케줄링 및 시그널 브로드캐스트
- 수동 탐색(Search) 서비스 흐름
- 템플릿 관리 및 데이터 조회 API

## 개요

본 도메인은 분석 조건 템플릿을 관리하고, 정의된 조건 트리에 따라 과거 데이터 및 실시간 데이터를 탐색하여 특정 패턴을 감지하는 기능을 제공합니다.

`AnalysisTemplateController`는 템플릿의 생성, 수정, 삭제 및 시그널 조회와 검색을 위한 REST 엔드포인트를 제공하며, `AnalysisTemplateService`는 템플릿 CRUD 작업과 더불어 특정 시간 범위 내의 데이터(delta) 조회 및 과거 시그널 히스토리 조회를 담당합니다.

데이터 탐색 로직은 `AnalysisDetectionEngine`이 핵심 역할을 수행하며, `ConditionTreeDto` 구조를 기반으로 `VOLUME_SPIKE`, `PRICE_CHANGE`, `DELTA`, `TIME_RANGE`와 같은 다양한 조건 유닛을 평가하여 매칭되는 봉(candle) 인덱스를 반환합니다.

자동화된 탐지 프로세스는 `AnalysisDetectionScheduler`를 통해 주기적으로 실행됩니다. 리더 노드 환경에서 스케줄러는 저장된 모든 템플릿을 불러와 최근 데이터에 적용하며, 조건이 충족될 경우 `SignalSseService`를 통해 매칭 이벤트를 브로드캐스트합니다.

또한, `AnalysisSearchService`는 사용자의 수동 탐색 요청(`AnalysisSearchRequest`)에 따라 지정된 타임프레임(1m/5m) 내에서 조건에 부합하는 봉의 시간 정보를 검색합니다.

## 데이터 모델 및 엔티티 구조

### 데이터 모델 및 엔티티 구조

`analysis_template` 테이블과 매핑되는 `AnalysisTemplate` 엔티티는 템플릿의 이름(`name`), 조건 트리 JSON 데이터(`conditions`), 팔레트 정보(`palette`), 그리고 생성 및 수정 일시(`createdAt`, `updatedAt`)를 관리합니다. (FILE: springboot/src/main/java/com/chs/springboot/domain/analysis/model/AnalysisTemplate.java)

데이터 전송을 위한 DTO 구조는 다음과 같습니다:

*   **Template 관련 DTO**:
    *   `TemplateRequestDto`: 템플릿 생성 및 수정을 위한 객체로 이름, 조건, 팔레트 정보를 포함합니다. (FILE: springboot/src/main/java/com/chs/springboot/domain/analysis/dto/TemplateRequestDto.java)
    *   `TemplateResponseDto`: 생성된 템플릿의 ID, 이름, 조건, 팔레트 및 생성/수정 일시를 포함하여 반환합니다. (FILE: springboot/src/main/java/com/chs/springboot/domain/analysis/dto/TemplateResponseDto.java)
*   **Condition Tree 관련 DTO**: 템플릿의 `conditions` 필드에 저장되는 JSON 구조를 정의합니다.
    *   `ConditionTreeDto`: 최상위 루트 객체로, 그룹들의 리스트(`groups`), 그룹 간 연산자(`groupOperator`), 팔레트 정보(`palette`)를 가집니다. (FILE: springboot/src/main/java/com/chs/springboot/domain/analysis/dto/ConditionTreeDto.java)
    *   `ConditionGroupDto`: 개별 조건 그룹을 정의하며, 그룹 내 연산자(`operator`), 단위 리스트(`units`), 단위 간 연산자(`unitOperators`)를 포함합니다. (FILE: springboot/src/main/java/com/chs/springboot/domain/analysis/dto/ConditionGroupDto.java)
    *   `ConditionUnitDto`: 실제 비교 로직이 담기는 최소 단위로, 타입(`type`), 연산자(`op`), 값(`value`), 부호(`sign`), 시간 범위 설정(`startHour`, `startMinute`, `endHour`, `endMinute`), 결과 반전 여부(`not`)를 포함합니다. (FILE: springboot/src/main/java/com/chs/springboot/domain/analysis/dto/ConditionUnitDto.java)
*   **Search 관련 DTO**:
    *   `AnalysisSearchRequest`: 수동 탐색을 위한 요청 객체로 심볼, 타임프레임, 시간 범위(`fromMs`, `toMs`), 그리고 상세 조건(`conditions`)을 포함합니다. (FILE: springboot/src/main/java/com/chs/springboot/domain/analysis/dto/AnalysisSearchRequest.java)
    *   `AnalysisSearchRequest.Conditions`: 탐색에 필요한 가격 변동률(`priceChangeRate`), 허용 오차(`rateTolerance`), 총 거래량(`totalVolume`), 거래량 허용 오차(`volTolerance`), 필터 사용 여부(`useRateFilter`, `useVolFilter`)를 정의합니다. (FILE: springboot/src/main/java/com/chs/springboot/domain/analysis/dto/AnalysisSearchRequest.java)

## 조건 트리(Condition Tree) 스펙 및 DTO 정의

조건 트리는 `ConditionTreeDto`를 루트로 하며, 논리적 그룹과 개별 조건을 계층적으로 구조화한다.

- **ConditionTreeDto**: 전체 조건 트리의 최상위 객체이다. `groups` 리스트를 통해 여러 개의 `ConditionGroupDto`를 포함하며, 그룹 간의 논리 연산 방식인 `groupOperator` (AND | OR)와 시각적 표현을 위한 `palette` 정보를 가진다.
  - `springboot/src/main/java/com/chs/springboot/domain/analysis/dto/ConditionTreeDto.java`

- **ConditionGroupDto**: 특정 논리 그룹을 정의한다. `units` 리스트를 통해 여러 개의 `ConditionUnitDto`를 포함하며, 그룹 내 단위들 사이의 연산 방식인 `operator` (AND | OR | NOT)를 지정할 수 있다.
  - `springboot/src/main/java/com/chs/springboot/domain/analysis/dto/ConditionGroupDto.java`

- **ConditionUnitDto**: 실제 비교 연산이 이루어지는 최소 단위이다.
    - `type`: 조건의 종류를 결정한다 (VOLUME_SPIKE, PRICE_CHANGE, DELTA, TIME_RANGE).
    - `op`: 비교 연산자를 지정한다 (GT, GTE, LT, LTE, POSITIVE, NEGATIVE).
    - `value`: 비교 대상이 되는 수치 값이다.
    - `sign`: DELTA 타입 전용으로, 결과의 양수/음수 여부를 판단한다 (POSITIVE | NEGATIVE).
    - `startHour`, `startMinute`, `endHour`, `endMinute`: TIME_RANGE 타입에서 시간 범위를 지정할 때 사용한다.
    - `not`: `true`일 경우 해당 조건의 결과값을 반전시킨다.
  - `springboot/src/main/java/com/chs/springboot/domain/analysis/dto/ConditionUnitDto.java`

## 탐지 엔진(Detection Engine) 로직

`AnalysisDetectionEngine` 클래스는 `ConditionTreeDto` 구조를 기반으로 캔들 데이터(`CandleData`)에 대한 조건 일치 여부를 평가합니다.

`evaluate` 메서드는 `ConditionTreeDto`의 `groupOperator`를 확인하여, 모든 그룹이 일치해야 하는 경우(AND)와 하나라도 일치하면 되는 경우(OR)로 나누어 각 그룹을 평가합니다.

각 그룹 내의 조건들은 `evalGroup` 메서드를 통해 처리됩니다.
- `NOT` 연산자: 그룹 내 첫 번째 유닛의 평가 결과를 반전시킵니다.
- `OR` 연산자: 그룹 내 유닛 중 하나라도 일치하면 참을 반환합니다.
- `AND` (기본값): 그룹 내 모든 유닛이 일치해야 참을 반환합니다.

개별 조건인 `ConditionUnitDto`는 `evalUnit` 메서드를 통해 다음과 같은 4가지 타입으로 평가됩니다.
- `VOLUME_SPIKE`: 최근 20개의 봉(`REF_BARS`) 평균 거래량 대비 현재 봉의 거래량 비율을 계산하여 `unit.getValue()`와 비교합니다.
- `PRICE_CHANGE`: 현재 봉의 시가 대비 종가의 절대 변동률을 계산하여 비교합니다.
- `DELTA`: 캔들의 `delta` 값을 기준으로 `POSITIVE`, `NEGATIVE` 또는 설정된 `value`와 비교합니다.
- `TIME_RANGE`: 캔들의 시간(`timeMs`)을 UTC 기준으로 변환하여 설정된 `startHour/minute`와 `endHour/minute` 사이의 범위에 있는지 확인합니다.

모든 평가 결과는 `unit.getNot()` 필드 값에 따라 최종적으로 반전될 수 있습니다. 비교 연산은 `compare` 메서드를 통해 `GT`, `GTE`, `LT`, `LTE` 연산자를 지원합니다.

## 실시간 스케줄링 및 시그널 브로드캐스트

`AnalysisDetectionScheduler` 클래스는 1분 주기로 실행되는 스케줄러로서, 리더 노드인 경우에만 동작하도록 설계되었습니다. `leaderElectionService.isLeader()`를 통해 리더 여부를 확인한 후, `analysisTemplateRepository`에서 모든 템플릿을 최신 생성일 순으로 가져옵니다.

스케줄링 프로세스는 다음과 같이 진행됩니다:
1. `SYMBOLS`에 정의된 각 심볼(BTCUSDT, ENAUSDT)에 대해 `agg1mRepository.findTopNWithCombinedDelta`를 호출하여 최근 1440개의 봉 데이터를 가져옵니다.
2. 가져온 데이터는 `toCandles` 메서드를 통해 `AnalysisDetectionEngine.CandleData` 객체 리스트로 변환됩니다.
3. 각 템플릿의 `conditions` JSON을 `objectMapper`를 사용하여 `ConditionTreeDto`로 역직렬화합니다.
4. `detectionEngine.evaluate(klineData, tree)`를 호출하여 조건에 매칭되는 봉의 인덱스 목록을 추출합니다.
5. 매칭된 결과가 존재할 경우, `symbol`, `templateId`, `templateName`, `matchCount`, `lastMatchIdx`를 포함한 페이로드를 생성하여 `signalSseService.broadcastAnalysisMatch(payload)`를 통해 SSE 이벤트를 브로드캐스트합니다.

이 과정에서 발생하는 예외는 로그에 기록되며, 특정 템플릿 처리 실패가 전체 스케줄링 프로세스에 영향을 주지 않도록 예외 처리가 되어 있습니다.

## 수동 탐색(Search) 서비스 흐름

사용자가 `AnalysisSearchRequest`를 포함한 요청을 보내면, `AnalysisTemplateController.search` 메서드가 호출되며 `AnalysisSearchService.search`로 요청을 전달합니다. (`AnalysisTemplateController.java`, `AnalysisSearchService.java`)

`AnalysisSearchService.search` 메서드는 요청된 조건(`Conditions`)을 바탕으로 거래량 허용 범위(`volMin`, `volMax`)를 계산합니다. 이후 요청에 포함된 `timeframe` 값에 따라 `1m`인 경우 `aggTrade1mRepository.findAllSimilarCandles`를, 그 외(예: `5m`)의 경우 `aggTrade5mRepository.findAllSimilarCandles`를 호출하여 조건에 부합하는 데이터를 조회합니다. (`AnalysisSearchService.java`)

조회된 결과는 `Object[]` 형태의 리스트로 반환되며, 서비스 계층에서는 각 행의 첫 번째 요소(index 0)를 `Long` 타입으로 변환하여 매칭된 봉의 `candle_time_ms` 목록을 생성합니다. (`AnalysisSearchService.java`)

최종적으로 생성된 `candle_time_ms` 목록이 리스트 형태로 컨트롤러에 반환됩니다. (`AnalysisSearchService.java`)

## 템플릿 관리 및 데이터 조회 API

`AnalysisTemplateController`는 템플릿의 생성, 수정, 삭제 및 데이터 조회를 위한 엔드포인트를 제공합니다.

*   **템플릿 목록 조회**: `templateService.findAll()`을 호출하여 생성일시 역순으로 정렬된 모든 템플릿 목록을 `TemplateResponseDto` 형태로 반환합니다. (`AnalysisTemplateController.java`, `AnalysisTemplateService.java`)
*   **템플릿 생성**: `TemplateRequestDto`를 통해 전달된 이름, 조건(JSON), 팔레트 정보를 사용하여 새로운 템플릿을 저장합니다. (`AnalysisTemplateController.java`, `AnalysisTemplateService.java`)
*   **템플릿 이름 및 정보 수정**: 특정 ID의 템플릿을 찾아 이름, 조건, 팔레트 정보를 업데이트합니다. (`AnalysisTemplateController.java`, `AnalysisTemplateService.java`)
*   **템플릿 삭제**: 특정 ID의 템플릿을 삭제합니다. (`AnalysisTemplateController.java`, `AnalysisTemplateService.java`)
*   **Delta 데이터 조회**: 특정 심볼과 시간 범위, 인터벌(1m/5m)에 따른 델타 데이터를 조회합니다. `interval` 값에 따라 `agg1mRepository` 또는 `agg5mRepository`로 라우팅됩니다. (`AnalysisTemplateController.java`, `AnalysisTemplateService.java`)
*   **템플릿 기준 시그널 날짜 조회**: 특정 템플릿의 조건(`conditions`)을 기반으로, 지정된 `days` 범위 내의 5분봉 데이터를 분석하여 조건에 매칭되는 시그널이 발생한 날짜와 해당 시점의 캔들 데이터를 반환합니다. (`AnalysisTemplateController.java`, `AnalysisTemplateService.java`)
