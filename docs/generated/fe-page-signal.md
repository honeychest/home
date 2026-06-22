# 프론트 페이지: signal

> 이 문서는 로컬 LLM(gemma-4-26b-a4b-it-mlx)이 소스 코드를 근거로 자동 생성했다. 검증 전 초안이다.

## 역할 요약

**한 줄 정의** — 특정 코인의 실시간 매수/매도 에너지·청산(liquidation)·오픈포지션(OI)을 한 화면에서 보며 단기 매매 신호를 포착하는 실시간 대시보드.

**누가·언제 쓰나** — 단타 트레이더가 변동성 큰 구간에서 롱/숏 힘겨루기와 청산 흐름을 즉시 확인하고 싶을 때.

**핵심 기능 3가지**
1. 매수/매도 에너지·TugOfWar(줄다리기) 시각화
2. 실시간 캔들·OI·청산 차트
3. 분석 템플릿 기반 패턴 표시(PatternStrip)

---

## 목차
- 개요 및 레이아웃 구조
- 데이터 수집 파이프라인 (REST API & WebSocket)
- 실시간 상태 관리 및 런타임 모델링
- 시각화 엔진: 차트 및 게이지 시스템
- 패턴 탐지 로직 및 분석 엔진
- 사용자 인터페이스: 패널 및 대시보드 구성
- 설정 관리 및 파라미터 동기화

## 개요 및 레이아웃 구조

이 도메인은 실시간 시장 데이터(에너지, 청산, 오픈 포지션)를 시각화하여 분석하는 대시보드 형태의 페이지로, 사용자의 디바이스 환경에 따라 두 가지 레이아웃을 제공합니다.

데스크톱 환경에서는 12컬럼 그리드 시스템을 기반으로 구성됩니다. 상단에는 심볼 선택, 시간 범위 설정 및 펀딩 레이트 정보를 포함하는 `TopBar.jsx`가 위치합니다. 중앙 영역은 크게 세 부분으로 나뉩니다. 좌측에는 `LongPanel.jsx`와 `ShortLiqPanel.jsx`가, 우측에는 `ShortPanel.jsx`와 `LiquidationPanel.jsx`가 배치되어 에너지 및 청산 데이터를 대칭적으로 보여줍니다. 중앙의 `MainCore.jsx`는 상단에 `TradingViewWidget.jsx`를, 중간에 `DivergenceBar.jsx`를, 하단에 `MiniChartPlaceholder.jsx`를 포함하여 핵심 차트 정보를 제공합니다. 최하단에는 `PatternStrip.jsx`가 전체 너비를 차지하며 분석 템플릿 기반의 패턴 정보를 표시합니다.

모바일 환경에서는 `isMobile` 상태에 따라 레이아웃이 재구성됩니다. 상단에는 `TopBar.jsx`가 위치하며, 그 아래로 에너지 및 튜그오브워(TugOfWar) 애니메이션이 포함된 미니 차트 영역, 그리고 에너지/청산 데이터가 2x2 그리드 형태로 배치됩니다. 구체적으로는 `LongPanel.jsx`/`ShortPanel.jsx`가 상단에, `ShortLiqPanel.jsx`/`LiquidationPanel.jsx`가 하단에 배치되어 좁은 화면에서도 정보를 효율적으로 확인할 수 있도록 설계되었습니다.

## 데이터 수집 파이프라인 (REST API & WebSocket)

데이터 수집은 초기 히스토리 로드를 위한 REST API 호출과 실시간 데이터 업데이트를 위한 WebSocket 연결으로 구성된다.

**1. 초기 히스토리 및 파라미터 로드 (REST API)**
시스템은 페이지 진입 시 심볼(symbol)과 설정된 시간 범위(timeRange)를 기반으로 다음과 같은 데이터를 비동기적으로 호출한다.
* **초기 데이터 및 파라미터:** `/api/signal/init`을 통해 초기 상태를 로드하며, `/api/signal/params`를 통해 해당 심볼의 편집 권한(`canEdit`) 및 분석 파라미터를 가져온다. (`frontend/src/page/signal/SignalPage.jsx`, `frontend/src/page/signal/TopBar.jsx`)
* **히스토리 데이터:** `/api/signal/history`를 통해 에너지, 청산 이벤트, OI(Open Interest) 등의 과거 데이터를 조회한다. (`frontend/src/page/signal/SignalPage.jsx`)
* **캔들 및 날짜 정보:** `/api/signal/candles`를 통해 특정 날짜와 범위에 해당하는 캔들 데이터를, `/api/signal/candles/dates`를 통해 심볼의 보유 날짜 목록을 조회한다. (`frontend/src/page/signal/hooks/useSignalCandles.js`, `frontend/src/page/signal/SignalPage.jsx`)
* **분석 템플릿:** `/api/analysis/templates`를 통해 분석에 사용될 템플릿 목록을 가져온다. (`frontend/src/page/signal/SignalPage.jsx`)

**2. 실시간 데이터 스트리밍 (WebSocket)**
실시간 캔들 및 상태 업데이트는 WebSocket을 통해 수행된다.
* **연결 구조:** `/ws/candle/{interval}?symbol={symbol}` 엔드포인트를 통해 연결된다. `CandleChart`와 `StealthCaseViewer`는 각각의 목적에 맞는 인터벌을 사용하여 실시간 데이터를 수신한다. (`frontend/src/page/signal/components/CandleChart.jsx`, `frontend/src/page/signal/components/stealth/StealthCaseViewer.jsx`)
* **데이터 처리 흐름:** 
    * `CandleChart`는 수신된 메시지의 `is_closed` 값에 따라 실시간 캔들 업데이트(`series.update`) 또는 봉 마감 시 콜백(`onCandleUpdate`, `onCandleTime`)을 실행한다. (`frontend/src/page/signal/components/CandleChart.jsx`)
    * `StealthCaseViewer`는 `is_closed: false`인 경우 진행 중인 봉(live candle)을 갱신하고, `is_closed: true`인 경우 상태를 전환하여 봉 마감 처리를 수행한다. (`frontend/src/page/signal/components/stealth/StealthCaseViewer.jsx`)
* **SSE(Server-Sent Events) 연동:** `useSignalSse` 훅을 통해 전달되는 `aggTrades`, `forceOrders`, `latestOi` 데이터는 수신 즉시 런타임 상태(`runtimeState`)에 반영되어 에너지 및 청산 데이터로 누적된다. (`frontend/src/page/signal/SignalPage.jsx`, `frontend/src/page/signal/model/signalRuntimeModel.js`)

## 실시간 상태 관리 및 런타임 모델링

`frontend/src/page/signal/model/signalRuntimeModel.js`에서 정의된 `createSignalRuntimeState` 함수를 통해 초기 상태 객체를 생성하며, 여기에는 에너지(long/short), 트레이드 기록, 청산 이벤트, OI 히스토리, 패턴 및 캔들 데이터가 포함됩니다.

실시간 데이터 업데이트는 다음과 같은 규칙에 따라 상태를 변형합니다:
* **Aggregated Trades**: `applyAggTrade` 함수는 수신된 트레이드의 `isBuyerMaker` 여부에 따라 에너지를 가감하고, 지정된 `TRADE_BUFFER_SIZE`(20)를 유지하며 트레이드 목록을 관리합니다. (`frontend/src/page/signal/model/signalRuntimeModel.js`)
* **Force Orders (Liquidation)**: `applyForceOrder` 함수는 주문의 `side`에 따라 반대 포지션의 에너지를 차감하고, 청산 누계액(`longLiqTotal` 또는 `shortLiqTotal`)을 업데이트하며, 지정된 `LIQUIDATION_BUFFER_SIZE`(50) 내에서 청산 이벤트를 관리합니다. (`frontend/src/page/signal/model/signalRuntimeModel.js`)
* **Open Interest (OI)**: `appendOi` 함수는 수신된 OI 데이터를 `OI_BUFFER_SIZE`(5000) 범위 내에서 히스토리에 누적합니다. (`frontend/src/page/signal/model/signalRuntimeModel.js`)
* **Candle Data**: `appendCandle` 함수는 수신된 캔들 데이터를 히스토리에 추가합니다. (`frontend/src/page/signal/model/signalRuntimeModel.js`)

`SignalPage.jsx`에서는 심볼이나 타임레인지 변경 시 `resetSignalRuntimeState`를 호출하여 상태를 초기화하며, SSE(Server-Sent Events)를 통해 들어오는 `aggTrades`, `forceOrders`, `latestOi` 데이터를 위 모델 함수들을 사용하여 실시간으로 반영합니다. (`frontend/src/page/signal/SignalPage.jsx`, `frontend/src/page/signal/model/signalRuntimeModel.js`)

## 시각화 엔진: 차트 및 게이지 시스템

`lightweight-charts` 라이브러리를 활용하여 다양한 데이터 시각화를 구현한다.

**1. 캔들 및 라인 차트 시스템**
* **CandleChart**: `lightweight-charts`의 `CandlestickSeries`를 사용하여 캔들 차트를 렌더링한다. WebSocket을 통해 실시간으로 수신되는 데이터를 `series.update()`로 반영하며, 봉이 마감될 경우(`is_closed: true`) `onCandleUpdate` 콜백을 통해 상위 컴포넌트에 데이터를 전달한다. (`frontend/src/page/signal/components/CandleChart.jsx`)
* **OiLineChart**: `AreaSeries`를 사용하여 오픈 포지션(OI) 변화를 면적 차트로 시각화한다. 데이터 업데이트 시 `rangeMs` 기준의 슬라이싱을 수행하며, 가격 변동에 따라 차트 선과 면적 색상이 동적으로 변경된다. (`frontend/src/page/signal/components/OiLineChart.jsx`)

**2. 게이지 및 애니메이션 시스템**
* **EnergyGauge**: `echarts` 라이브러리의 `gauge` 타입을 사용하여 에너지 비율을 반원 형태의 게이지로 시각화한다. `longEnergy`와 `shortEnergy` 비율에 따라 바늘의 위치와 색상이 결정되며, 데이터 변화 시 자연스러운 움직임을 위해 `jitterTimerRef`를 이용한 미세 진동 애니메이션이 적용된다. (`frontend/src/page/signal/components/EnergyGauge.jsx`)
* **TugOfWar**: `longEnergy`와 `shortEnergy` 사이의 힘의 균형을 시각화하는 애니메이션 컴포넌트이다. 중앙의 노드는 에너지 비율에 따라 좌우로 이동하며, 양 끝단과 중앙부에는 `keyframes`를 활용한 펄스(Pulse) 및 진동 효과가 적용되어 긴장감을 표현한다. (`frontend/src/page/signal/components/TugOfWar.jsx`)

**3. UI 요소 및 인터랙션**
* **Tooltip**: 차트 내 마우스 이동(`subscribeCrosshairMove`) 시 툴팁이 생성된다. `CandleChart`는 시간, 종가, Delta 정보를 표시하며, `OiLineChart`는 날짜와 OI 수치 및 해당 시점의 가격 정보를 표시한다. (`frontend/src/page/signal/components/CandleChart.jsx`, `frontend/src/page/signal/components/OiLineChart.jsx`)
* **StealthBadge**: 분석 결과에 따른 방향성(Up, Down, Sideways 등)을 아이콘과 색상으로 요약하여 표시한다. (`frontend/src/page/signal/components/stealth/StealthBadge.jsx`)

## 패턴 탐지 로직 및 분석 엔진

패턴 탐지는 `frontend/src/page/signal/engine/detectionEngine.js`의 `detect` 함수를 통해 수행된다. 이 함수는 주어진 캔들 배열(`candles`)을 순회하며, 설정된 참조 봉 개수(`params.refBars`)만큼의 이전 데이터(`prev`)와 현재 봉(`cur`)을 비교하여 모든 조건이 충족될 경우 해당 인덱스를 결과로 반환한다.

`detectAB` 함수는 두 가지 유형의 패턴을 동시에 탐지하며, 특정 조건이 중복될 경우 `typeB`로 분류한다.

1. **Type A (스텔스 거래/의심)**: `frontend/src/page/signal/engine/conditions.js`에 정의된 다음 두 조건의 교집합을 탐지한다.
    * **거래량 배수 조건 (`volumeMultiplierCondition`)**: 현재 봉의 거래량이 직전 $N$개 봉 평균 거래량에 특정 배수(`params.volumeMultiplier`)를 곱한 값보다 크거나 같아야 한다.
    * **인사이드 바 조건 (`insideBarCondition`)**: 현재 봉의 고가가 직전 $N$개 봉 중 최고가보다 낮고, 저가가 직전 $N$개 봉 중 최저가보다 높아야 한다.

2. **Type B (스텔스 의심)**: `frontend/src/page/signal/engine/conditions.js`에 정의된 다음 세 조건의 교집합을 탐지한다.
    * **도지봉 제외 조건 (`notAllDojisCondition`)**: 직전 $N$개 봉이 모두 몸통이 없는 도지봉(몸통 크기=0)인 경우를 제외한다.
    * **거래량 배수 조건 (`volumeMultiplierCondition`)**: 위 Type A와 동일한 거래량 조건을 적용한다.
    * **몸통 비율 조건 (`bodyRatioCondition`)**: 현재 봉의 몸통 크기(종가와 시가의 차이의 절댓값)가 직전 $N$개 봉 중 몸통 크기가 가장 작은 값보다 작아야 한다.

탐지된 결과는 `frontend/src/page/signal/components/stealth/StealthCaseViewer.jsx`를 통해 슬롯에 배치되며, `frontend/src/page/signal/components/stealth/StealthSlot.jsx`에서 `highlights`로 시각화된다.

## 사용자 인터페이스: 패널 및 대시보드 구성

사용자 인터페이스는 데스크톱과 모바일 환경에 따라 레이아웃이 분기되며, 데이터 시각화와 실시간 상태 모니터링을 위한 다양한 패널로 구성됩니다.

**1. 데스크톱 레이아웃 (Grid 구조)**
데스크톱 환경에서는 12컬럼 그리드 시스템을 사용하여 정보를 배치합니다.
* **상단 영역**: `TopBar.jsx`가 위치하며 심볼 선택, 시간 범위 설정, 펀딩 레이트 표시 및 관리자용 파라미터 설정(`ParamPanel.jsx`) 기능을 제공합니다.
* **좌측 영역**: `LongPanel.jsx`와 `ShortLiqPanel.jsx`가 수직으로 배치되어 롱 에너지 및 숏 청산 이벤트를 표시합니다.
* **중앙 영역**: `MainCore.jsx`가 핵심 대시보드 역할을 수행합니다. 상단에는 `TradingViewWidget.jsx`가, 중간에는 `DivergenceBar.jsx`가, 하단에는 `MiniChartPlaceholder.jsx`가 배치됩니다.
* **우측 영역**: `ShortPanel.jsx`와 `LiquidationPanel.jsx`가 배치되어 숏 에너지 및 롱 청산 이벤트를 표시합니다.
* **하단 영역**: `PatternStrip.jsx`가 전체 너비를 차지하며 스텔스 패턴 분석 정보를 제공합니다.

**2. 모바일 레이아out (Column 구조)**
모바일 환경에서는 `isMobile` 상태에 따라 수직 스택 구조로 전환됩니다.
* **상단**: `TopBar.jsx`가 콤팩트 모드로 동작합니다.
* **중앙**: `EnergyGauge.jsx`와 `TugOfWar` 애니메이션이 결합된 에너지 지표 영역이 위치하며, 그 아래로 `LongPanel.jsx`/`ShortPanel.jsx` 및 `ShortLiqPanel.jsx`/`LiquidationPanel.jsx`가 2x2 그리드 형태로 배치됩니다.

**3. 주요 컴포넌트 및 데이터 시각화 패널**
* **에너지 및 트레이드 패널**: `LongPanel.jsx`와 `ShortPanel.jsx`는 누적 에너지 수치와 실시간 틱 테이프(trades)를 표시합니다. `LiquidationPanel.jsx`와 `ShortLiqPanel.jsx`는 각각 롱/숏 청산 이벤트 목록과 누계 합계를 관리합니다.
* **미니 차트 및 시그널 슬롯**: `MiniChartPlaceholder.jsx`는 세 개의 슬롯으로 구성됩니다.
    * **FUTURES 슬롯**: `CandleChart.jsx`를 통해 실시간 캔들 차트를 제공합니다.
    * **Signal 슬롯**: `EnergyGauge.jsx`와 `TugOfWar.jsx`를 통해 에너지 균형을 시각화합니다.
    * **오픈 포지션 볼륨 슬롯**: `OiLineChart.jsx`를 통해 미결제약정(OI) 변화 추이를 보여줍니다.
* **스텔스 분석 패널**: `PatternStrip.jsx` 내의 `StealthCaseViewer.jsx`는 분석 템플릿에 따른 슬롯들을 생성하며, 각 슬롯은 `StealthSlot.jsx`를 통해 데이터와 상태(WATCHING, RECONNECTING 등)를 시각화합니다. `StealthWatcherPanel.jsx`는 중앙 슬롯에 상태 레이블과 리셋 버튼을 오버레이로 제공합니다.

## 설정 관리 및 파라미터 동기화

사용자가 파라미터를 변경하면 `ParamPanel.jsx`의 `handleSave` 메서드가 호출되어 `onParamsSave` 콜백을 실행하며, 이는 `SignalPage.jsx`의 `handleParamsSave`로 전달됩니다. `handleParamsSave`는 `apiClient.put`을 통해 서버에 변경된 값을 전송하며, 요청 성공 시 최신 파라미터 상태를 `setParams`로 업데이트합니다. 만약 API 요청이 실패할 경우, `ParamPanel.jsx`는 `prevParamsRef.current`에 저장해둔 이전 값을 사용하여 로컬 상태를 롤백합니다.

또한, `SignalPage.jsx`에서 관리되는 `params` 상태는 `TopBar.jsx`를 통해 템플릿 선택 및 심볼 변경 시 참조되며, `PatternStrip.jsx`로 전달되는 `paletteLevel` 등과 연동되어 분석 엔진의 재계산을 트리거하는 역할을 합니다. `ParamPanel.jsx` 내의 슬라이더 조작 시에는 `setLocal`을 통해 즉각적으로 값이 반영되지만, 실제 서버 데이터와의 동기화는 '적용' 버튼을 누르는 시점에 이루어집니다.
