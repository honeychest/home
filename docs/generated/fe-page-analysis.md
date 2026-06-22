# 프론트 페이지: analysis

> 이 문서는 로컬 LLM(gemma-4-26b-a4b-it-mlx)이 소스 코드를 근거로 자동 생성했다. 검증 전 초안이다.

## 역할 요약

**한 줄 정의** — 거래량 급증·가격 변동·델타·시간대 같은 복합 조건으로 과거 거래 패턴을 차트에서 찾아내고, 그 조건을 템플릿으로 저장·관리하는 페이지.

**누가·언제 쓰나** — 특정 패턴이 과거 언제 나왔는지 조건을 조합해 되짚어보고, 재사용할 조건을 템플릿화하고 싶을 때.

**핵심 기능 3가지**
1. 조건 빌더(트리)로 복합 조건 구성
2. 탐지 엔진이 조건에 맞는 봉을 차트에 하이라이트
3. 조건 템플릿 저장·불러오기

---

## 목차
- 개요 및 도메인 구조
- 데이터 로드 및 실시간 동기화 파이프라인
- 조건 빌더 및 트리 구조 관리
- 탐지 엔진: 조건 평가 및 매칭 로직
- 사례 패널: 데이터 시각화 및 네비게이션
- 템플릿 관리 시스템
- 유사 패턴 수동 탐색 (Signal Search)

## 개요 및 도메인 구조

`analysis` 도메인은 사용자가 설정한 복합적인 조건에 부합하는 과거의 거래 패턴을 차트 상에서 시각적으로 탐색하고, 이를 템플릿화하여 관리할 수 있는 기능을 제공합니다.

**1. 데이터 흐름 및 분석 엔진**
데이터는 바이낸스 API를 통해 캔들(Kline) 데이터를 가져오고, 백엔드로부터 해당 시점의 `delta` 및 `volume` 정보를 병합하여 구축됩니다. `frontend/src/page/analysis/hooks/useBinanceKlines.js`에서 생성된 `klineData`는 `frontend/src/page/analysis/engine/detectionEngine.js`의 `evaluate` 메서드로 전달됩니다. 이 엔진은 `frontend/src/page/analysis/engine/conditionRegistry.js`에 등록된 개별 조건 플러그인(`VOLUME_SPIKE`, `PRICE_CHANGE`, `DELTA`, `TIME_RANGE`)을 사용하여 `conditionTree` 구조를 순회하며 조건에 부합하는 봉의 인덱스(`matchedIndices`)를 산출합니다.

**2. UI 구성 및 인터랙션**
페이지는 크게 세 영역으로 구분됩니다.
*   **좌측 영역**: `frontend/src/page/analysis/components/MainChart.jsx`를 통해 시각화된 캔들 차트와 `frontend/src/page/analysis/components/ConditionBuilder.jsx`를 통한 조건 설정 인터페이스가 위치합니다. 사용자가 조건을 변경하면 `frontend/src/page/analysis/engine/detectionEngine.js`를 통해 즉시 재계산되어 차트 상에 하이라이트가 적용됩니다.
*   **우측 사례 패널**: `frontend/src/page/analysis/components/CasesPanel.jsx`는 계산된 `matchedIndices`를 기반으로 매칭된 사례들을 그리드 형태로 보여줍니다. 각 사례는 `frontend/src/page/analysis/components/CaseCard.jsx`를 통해 미니 차트로 시각화됩니다.
*   **하단 템플릿 바**: `frontend/src/page/analysis/components/TemplateBar.jsx`를 통해 저장된 조건 세트를 불러오거나 새로운 템플릿을 생성할 수 있습니다.

**3. 특수 기능: 유사 패턴 검색**
차트 상의 특정 봉을 더블클릭하면 `frontend/src/page/analysis/components/SignalSearchPopup.jsx`가 활성화됩니다. 이는 선택된 봉의 OHLC(시가, 고가, 저가, 종가) 및 거래량 정보를 바탕으로 사용자가 허용 오차 범위를 설정하여 유사한 패턴을 수동으로 검색할 수 있게 합니다. 검색 결과는 `frontend/src/page/analysis/model/analysisPageModel.js`의 `mapSearchTimesToIndices`를 통해 다시 차트의 매칭 인덱스로 변환됩니다.

## 데이터 로드 및 실시간 동기화 파이프라인

데이터 로드 및 실시간 동기화 파이프라인은 바이낸스 API를 통한 캔들 데이터(Kline) 확보와 백엔드로부터 제공되는 Delta 데이터를 병합하는 과정을 핵심으로 합니다.

`fetchKlines` 함수(`frontend/src/page/analysis/hooks/useBinanceKlines.js`)는 지정된 날짜 범위 내의 데이터를 일 단위로 분할하여 요청합니다. 5분봉(`5m`)은 하루치 데이터를 한 번의 요청으로 가져오지만, 1분봉(`1m`)은 하루치(1440분)를 `LIMIT_1M`(720봉) 단위로 두 번 나누어 호출합니다. 이때 바이낸스 API의 `429` 응답(Rate Limit) 발생 시 `retry-after` 헤더를 파싱하여 자동 재시도 로직을 수행합니다.

데이터 병합 과정에서는 `fetchKlines`가 호출될 때 백엔드 API(`apiClient.get('/api/analysis/delta')`)를 통해 해당 기간의 `delta` 및 `volume` 정보를 가져옵니다. 이후 바이낸스에서 가져온 캔들 데이터와 백엔드에서 받은 `deltaList`를 시간(`timeMs`) 기준으로 매핑하여, 각 캔들 객체에 `delta`와 `volume` 값을 주입합니다.

실시간 동기화는 WebSocket을 통해 이루어집니다. `MainChart` 컴포넌트(`frontend/src/page/analysis/components/MainChart.jsx`) 내의 `useEffect`는 심볼과 타임프레임이 변경될 때마다 백엔드의 WebSocket 엔드포인트(`ws/candle/{timeframe}?symbol={symbolUsdt}`)에 연결합니다. 수신된 메시지는 실시간으로 차트의 캔들을 업데이트하며, 만약 `is_closed` 값이 `true`인 완성된 봉이 들어올 경우 `onCandleClose` 콜백을 호출하여 상위 컴포넌트(`frontend/src/page/analysis/AnalysisPage.jsx`)의 `klineData` 상태와 동기화합니다.

## 조건 빌더 및 트리 구조 관리

조건 빌더는 `conditionTree` 상태를 기반으로 그룹과 조건을 계층적으로 구성하며, `frontend/src/page/analysis/components/ConditionBuilder.jsx`에서 전체적인 구조를 관리합니다.

- **트리 구조 및 연산자**: `conditionTree`는 여러 개의 `groups`와 그룹 간의 관계를 정의하는 `groupOperator`(AND/OR)로 구성됩니다. 각 그룹 내에는 여러 개의 `units`(조건 행)가 존재하며, `frontend/src/page/analysis/components/ConditionGroup.jsx`를 통해 그룹 내 조건 간의 연산자(`AND`, `OR`, `NOT`)가 관리됩니다.
- **조건 단위(Unit) 구성**: `frontend/src/page/analysis/components/ConditionRow.jsx`에서 개별 조건의 타입(`VOLUME_SPIKE`, `PRICE_CHANGE`, `DELTA`, `TIME_RANGE`), 비교 연산자, 값, 그리고 시각적 강조를 위한 팔레트(`LOW`, `MID`, `HIGH`)를 설정합니다.
- **계층적 평가 로직**: 
    - `frontend/src/page/analysis/engine/detectionEngine.js`의 `evaluate` 함수는 전체 트리를 순회하며 매칭되는 봉의 인덱스를 추출합니다.
    - `evalGroup` 함수는 그룹 내의 모든 `units`를 설정된 연산자에 따라 평가합니다.
    - `evalUnit` 함수는 `frontend/src/page/analysis/engine/conditionRegistry.js`에 등록된 각 타입별 평가 로직(`volumeSpike.js`, `priceChange.js`, `delta.js`, `timeRange.js`)을 호출하여 단일 조건의 참/거짓을 판별합니다.
- **팔레트 자동 계산**: `frontend/src/page/analysis/components/ConditionBuilder.jsx` 내의 `computeTreePalette` 함수는 설정된 모든 조건 중 가장 높은 강조 레벨을 계산하여 전체 트리에 적용할 `palette` 값을 결정합니다.
- **상태 동기화**: 사용자가 조건을 변경하면 `handleTreeChange`를 통해 새로운 트리가 생성되며, 이는 즉시 `frontend/s/page/analysis/engine/detectionEngine.js`의 평가 로직을 거쳐 매칭 결과(`matchedIndices`)로 반영됩니다.

## 탐지 엔진: 조건 평가 및 매칭 로직

조건 평가 및 매칭 로직은 `conditionTree`에 정의된 그룹과 조건들을 순회하며 각 봉(candle)의 인덱스를 추출하는 방식으로 동작한다.

`evaluate(klineData, conditionTree)` 함수는 `conditionTree` 내의 `groups`와 `groupOperator`를 기반으로 전체 매칭 여부를 결정한다. `groupOperator`가 `OR`인 경우 그룹 중 하나라도 조건을 만족하면 매칭되며, 그 외(`AND`)의 경우에는 모든 그룹이 조건을 만족해야 한다. (`frontend/src/page/analysis/engine/detectionEngine.js`)

각 그룹 내의 조건 평가는 다음과 같은 계층 구조를 가진다:
1. **그룹 평가 (`evalGroup`)**: 그룹 내의 `units`들을 순회하며 각 조건을 평가한다. `operator`가 `OR`이면 하나라도 만족 시 true, `NOT`이면 첫 번째 조건의 결과를 반전시키며, 기본값인 `AND`는 모든 조건이 만족되어야 true를 반환한다. (`frontend/src/page/analysis/engine/detectionEngine.js`)
2. **단일 조건 평가 (`evalUnit`)**: `conditionRegistry`에 등록된 각 타입별 엔진을 호출하여 결과를 얻는다. 이때 조건에 `not` 속성이 설정되어 있다면 결과값을 반전시킨다. (`frontend/src/page/analysis/engine/detectionEngine.js`)

세부 조건 타입별 평가 로직은 `conditionRegistry.js`를 통해 호출되며, 각 엔진의 동작은 다음과 같다:
* **VOLUME_SPIKE**: 현재 봉의 거래량이 직전 20개 봉 평균 거래량 대비 설정된 배수(`value`)를 만족하는지 비교한다. (`frontend/src/page/analysis/engine/conditions/volumeSpike.js`)
* **PRICE_CHANGE**: 해당 봉의 시가 대비 종가의 절대 등락폭(%)이 설정된 값(`value`)과 비교 연산자(`op`)를 만족하는지 확인한다. (`frontend/src/page/analysis/engine/conditions/priceChange.js`)
* **DELTA**: 봉의 `delta` 값을 기준으로 양수/음수 여부(`sign`)를 확인하거나, 설정된 수치(`value`)와 비교 연산자(`op`)를 통해 평가한다. (`frontend/src/page/analysis/engine/conditions/delta.js`)
* **TIME_RANGE**: 봉의 UTC 시간(시:분)이 설정된 시작 및 종료 범위 내에 있는지 확인한다. 자정을 넘기는 범위 설정도 지원한다. (`frontend/src/page/analysis/engine/conditions/timeRange.js`)

최종적으로 모든 조건을 통과한 봉의 인덱스들은 `results` 배열에 수집되어 매칭된 사례로 관리된다. (`frontend/src/page/analysis/engine/detectionEngine.js`)

## 사례 패널: 데이터 시각화 및 네비게이션

`frontend/src/page/analysis/components/CasesPanel.jsx`에서 관리되는 사례 패널은 `matchedIndices`를 기반으로 매칭된 봉들을 시각화합니다. 최신순(내림차순)으로 정렬된 인덱스 중 현재 페이지에 해당하는 18개의 사례를 추출하여 `3×6` 그리드 형태로 표시합니다. (`frontend/src/page/analysis/components/CasesPanel.jsx`)

각 사례는 `frontend/src/page/analysis/components/CaseCard.jsx`를 통해 개별 카드로 렌더링됩니다. 각 카드는 `matchIndex`에 해당하는 시점의 날짜와 시간을 표시하며, `build5mCandles` 함수를 통해 해당 시점 전후의 데이터를 기반으로 생성된 미니 차트를 보여줍니다. (`frontend/src/page/analysis/components/CaseCard.jsx`)

차트 시각화는 `MiniChart.jsx`를 사용하며, 설정된 `paletteLevel`에 따라 색상이 결정됩니다. `CaseCard.jsx`는 `matchIndex` 시점의 봉을 강조하기 위해 `highlights` 인자를 전달합니다. (`frontend/src/page/analysis/components/CaseCard.jsx`)

패널 하단의 네비게이션 버튼을 통해 페이지를 이동할 수 있습니다. `onPrev` 호출 시 이전 기간의 데이터를 추가로 로드하여 `hasPrevPage` 상태를 관리하며, `onNext` 호출 시 다음 페이지의 사례들을 탐색합니다. (`frontend/src/page/analysis/components/CasesPanel.jsx`)

## 템플릿 관리 시스템

템플릿은 `frontend/src/page/analysis/AnalysisPage.jsx`에서 관리되며, 사용자가 설정한 조건 트리(`conditionTree`)와 팔레트 정보를 저장하고 불러오는 기능을 제공합니다.

- **템플릿 생성 및 저장**: `frontend/src/page/analysis/components/TemplateBar.jsx`에서 사용자가 '저장' 버튼을 누르면 `AnalysisPage.jsx`의 `handleSaveClick`이 호출되어 입력 모드로 전환됩니다. 이후 `handleSaveConfirm` 메서드가 실행되며, 기존 템플릿과 이름이 같으면 `apiClient.put`을 통해 업데이트하고, 새로운 이름이면 `apiClient.post`를 통해 새 템플릿을 생성합니다. 저장 시 조건 트리는 `JSON.stringify`를 통해 문자열로 변환되어 전송됩니다.
- **템플릿 불러오기**: `frontend/src/page/analysis/components/TemplateBar.jsx`의 드롭다운 메뉴를 통해 저장된 템플릿 목록을 선택할 수 있습니다. `onSelect` 핸들러를 통해 `AnalysisPage.jsx`의 `handleSelectTemplate`이 호출되며, 선택된 템플릿의 `conditions` 데이터를 파싱하여 현재 페이지의 `conditionTree`에 적용합니다.
- **템플릿 관리**: `frontend/s/page/analysis/components/TemplateManagerModal.jsx`를 통해 저장된 템플릿 목록을 확인하고 관리할 수 있습니다.
    - **이름 변경**: `frontend/src/page/analysis/components/TemplateRow.jsx`에서 편집 모드로 전환하여 이름을 수정하고, `AnalysisPage.jsx`의 `handleRename`을 통해 `apiClient.put` 요청을 보냅니다.
    - **삭제**: `TemplateRow.jsx`에서 삭제 버튼을 누르면 `AnalysisPage.jsx`의 `handleDelete`가 호출되어 `apiClient.delete`를 통해 해당 템플릿을 삭제합니다.
    - **불러오기**: `TemplateRow.jsx`의 '불러오기' 버튼을 누르면 `onLoad`를 통해 템플릿 정보가 페이지에 적용됩니다.
- **초기 데이터 로드**: `AnalysisPage.jsx`의 초기 진입 시 `apiClient.get('/api/analysis/templates')`를 통해 전체 템플릿 목록을 가져와 `templates` 상태에 저장합니다.

## 유사 패턴 수동 탐색 (Signal Search)

차트 상의 특정 봉을 더블클릭하면 `frontend/src/page/analysis/components/SignalSearchPopup.jsx`가 활성화되어 유사한 거래 패턴을 검색할 수 있는 팝업이 나타납니다. 더블클릭 시 해당 봉의 OHLC(시가, 고가, 저가, 종가), 거래량, 이전 봉 종가(`prevClose`), 타임프레임, 심볼 정보가 `doubleClickData`로 전달됩니다. (`frontend/src/page/analysis/components/MainChart.jsx`, `frontend/src/page/analysis/components/SignalSearchPopup.jsx`)

사용자는 팝업 내에서 다음과 같은 조건들을 설정할 수 있습니다:
* **등락율**: 시가 대비 종가의 절대 등락폭(%)을 설정하며, 허용 범위(`rateTolerance`)를 지정할 수 있습니다. (`frontend/src/page/analysis/components/SignalSearchPopup.jsx`)
* **OHLC**: 특정 시점의 가격대를 직접 입력하여 범위를 지정합니다. (`frontend/src/page/analysis/components/SignalSearchPopup.jsx`)
* **거래대금**: 거래량 조건을 설정하며, 허용 범위(`volTolerance`)를 지정할 수 있습니다. (`frontend/src/page/analysis/components/SignalSearchPopup.jsx`)

설정된 값의 유효성 검사(예: 고가가 시가/종가보다 낮지 않은지 등)를 거친 후 '조회' 버튼을 누르면 `onSearch` 콜백이 호출됩니다. (`frontend/src/page/analysis/components/SignalSearchPopup.jsx`) 이때 전달되는 `requestBody`는 `buildAnalysisSearchRequest`를 통해 시작/종료 시간 정보와 결합되어 최종적인 검색 요청 객체로 구성됩니다. (`frontend/src/page/analysis/model/analysisPageModel.js`, `frontend/src/page/analysis/components/SignalSearchPopup.jsx`)
