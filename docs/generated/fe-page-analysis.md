# 프론트 페이지: analysis

> wiki-refresh로 실제 소스를 읽고 검증함(2026-06-24). 페이지 간 관계는 `index.md` 참고.

## 역할 요약
- **한 줄 정의** — 거래량 급증·가격 변동·델타·시간대 같은 복합 조건으로 과거 거래 패턴을 차트에서 찾아내고, 그 조건을 템플릿으로 저장·관리하는 페이지.
- **누가·언제 쓰나** — 특정 패턴이 과거 언제 나왔는지 조건을 조합해 되짚어보고, 재사용할 조건을 템플릿화하고 싶을 때.
- **핵심 기능** — ① 조건 빌더(트리)로 복합 조건 구성 ② 탐지 엔진이 맞는 봉을 차트에 하이라이트 ③ 조건 템플릿 저장·불러오기 + 유사 패턴 수동 검색.

## 이런 걸 물을 때 찾으면 된다 (검색 키워드)
- "분석 페이지 조건 빌더 / 조건 트리 / 그룹 연산자 AND OR NOT"
- "VOLUME_SPIKE PRICE_CHANGE DELTA TIME_RANGE 프론트 평가"
- "캔들은 어디서 받아와? 바이낸스 klines + 백엔드 delta 병합"
- "분석 템플릿 저장/불러오기 /api/analysis/templates"
- "유사 패턴 검색 / 봉 더블클릭 / OHLC 허용오차"

## 핵심 개념·용어
- **conditionTree**: `groups`(그룹 배열) + `groupOperator`(AND/OR). 각 그룹은 `units`(조건 행) + `operator`(AND/OR/NOT). 백엔드 `be-analysis`의 동일 스펙과 짝.
- **conditionRegistry**: 타입별 평가 플러그인(`volumeSpike/priceChange/delta/timeRange`)을 등록·호출.
- **matchedIndices**: 조건을 통과한 봉 인덱스 목록. 차트 하이라이트와 사례 패널의 입력.
- **palette(LOW/MID/HIGH)**: 조건 강조 레벨. `computeTreePalette`가 최고 레벨을 트리 전체에 적용.
- **klineData**: 바이낸스 캔들 + 백엔드 delta/volume을 시간 기준 병합한 분석용 봉 배열.

## 구조 / 흐름 (확인된 코드 기준)

파일 위치: `frontend/src/page/analysis/`

### 진입·레이아웃 — `AnalysisPage.jsx`
- 좌측 `MainChart`(캔들 차트)+`ConditionBuilder`(조건 설정), 우측 `CasesPanel`(매칭 사례 그리드), 하단 `TemplateBar`(템플릿 바). 조건 변경 시 즉시 재계산→차트 하이라이트.

### 데이터 로드·동기화 — `hooks/useBinanceKlines.js`, `components/MainChart.jsx`
- `fetchKlines`: 날짜 범위를 일 단위로 분할 요청. 5분봉은 하루 1회, 1분봉은 1440분을 `LIMIT_1M`(720봉) 단위로 2회. 바이낸스 `429`면 `retry-after` 파싱 후 재시도.
- 병합: `apiClient.get('/api/analysis/delta')`로 받은 `deltaList`를 `timeMs`로 매핑해 각 캔들에 `delta`/`volume` 주입.
- 실시간: `MainChart`가 `ws/candle/{timeframe}?symbol={symbolUsdt}` 연결, `is_closed:true` 봉은 `onCandleClose`로 상위 `klineData`와 동기화.

### 조건 빌더 — `components/ConditionBuilder.jsx`, `ConditionGroup.jsx`, `ConditionRow.jsx`
- 트리 구조(groups/groupOperator) + 그룹 내 units/operator. 행에서 타입·연산자·값·팔레트 설정. `handleTreeChange`로 새 트리 생성→즉시 평가.

### 탐지 엔진 — `engine/detectionEngine.js`, `engine/conditionRegistry.js`, `engine/conditions/*`
- `evaluate(klineData, conditionTree)`: `groupOperator` OR면 anyMatch, AND면 allMatch. `evalGroup`: OR/NOT/AND. `evalUnit`: registry의 타입별 엔진 호출 후 `not`이면 반전.
  - **VOLUME_SPIKE**: 직전 20봉 평균 거래량 대비 배수(`value`) 비교. **PRICE_CHANGE**: 시가 대비 종가 절대 등락폭(%) 비교. **DELTA**: `sign`(양/음) 또는 `value`+`op` 비교. **TIME_RANGE**: 봉 UTC 시:분이 범위 내(자정 넘김 지원).
  - (백엔드 `be-analysis`의 자바 엔진과 동일 로직 — JS/Java 일치 보장.)

### 사례 패널 — `components/CasesPanel.jsx`, `CaseCard.jsx`
- `matchedIndices`를 최신순 정렬, 페이지당 18개를 3×6 그리드로. 각 카드는 `MiniChart`(`paletteLevel` 색상)로 해당 시점 미니차트 + `highlights`로 매칭 봉 강조. `onPrev`(이전 기간 추가 로드)/`onNext`.

### 템플릿 관리 — `AnalysisPage.jsx`, `components/TemplateBar.jsx`, `TemplateManagerModal.jsx`, `TemplateRow.jsx`
- 초기 `apiClient.get('/api/analysis/templates')`. 저장 `handleSaveConfirm`: 같은 이름이면 `apiClient.put`, 새 이름이면 `apiClient.post`(조건트리는 `JSON.stringify`). 불러오기 `handleSelectTemplate`(conditions 파싱→트리 적용). 이름변경 `handleRename`(put)·삭제 `handleDelete`(delete).

### 유사 패턴 수동 검색 — `components/SignalSearchPopup.jsx`, `model/analysisPageModel.js`
- 봉 더블클릭→`doubleClickData`(OHLC·거래량·prevClose·타임프레임·심볼). 등락율(`rateTolerance`)·OHLC·거래대금(`volTolerance`) 설정→유효성 검사→`onSearch`. `buildAnalysisSearchRequest`로 요청 구성→백엔드 `POST /api/analysis/search`. 결과 시간은 `mapSearchTimesToIndices`로 차트 인덱스 변환.

## 연관 도메인
- 백엔드: `be-analysis`(템플릿·탐지·search), `be-binance`(1m/5m delta). 화면: `fe-page-signal`(스텔스 패턴 공유 개념). 상세 관계는 `index.md`.
