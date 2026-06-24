# 프론트 페이지: signal

> wiki-refresh로 실제 소스를 읽고 검증함(2026-06-24). 페이지 간 관계는 `index.md` 참고.

## 역할 요약
- **한 줄 정의** — 특정 코인의 실시간 매수/매도 에너지·청산(liquidation)·오픈포지션(OI)을 한 화면에서 보며 단기 매매 신호를 포착하는 실시간 대시보드.
- **누가·언제 쓰나** — 단타 트레이더가 변동성 큰 구간에서 롱/숏 힘겨루기와 청산 흐름을 즉시 확인하고 싶을 때.
- **핵심 기능** — ① 매수/매도 에너지·TugOfWar(줄다리기) 시각화 ② 실시간 캔들·OI·청산 차트 ③ 분석 템플릿 기반 패턴(PatternStrip/스텔스) 표시.

## 이런 걸 물을 때 찾으면 된다 (검색 키워드)
- "시그널 대시보드는 어떻게 동작해? 실시간 에너지/청산/OI"
- "매수매도 에너지 / TugOfWar / EnergyGauge 게이지"
- "스텔스 패턴 탐지 / 거래량 급증 / 인사이드바 / 도지"
- "시그널 데이터는 어디서 와? /api/signal init·history·candles, SSE"
- "실시간 캔들 WebSocket /ws/candle"

## 핵심 개념·용어
- **에너지(energy)**: 체결을 매수/매도로 분류해 누적한 힘 지표. `applyAggTrade`가 `isBuyerMaker`로 가감.
- **TugOfWar**: long/short 에너지 비율을 줄다리기 애니메이션으로 시각화.
- **OI(Open Interest)**: 미결제약정. `appendOi`로 히스토리 누적.
- **스텔스(Stealth) 패턴**: 거래량 급증 + 인사이드바/작은몸통 조건으로 잠복 매집 의심 봉을 탐지(Type A/B).
- **런타임 상태**: SSE/WS로 들어오는 데이터를 메모리에 누적한 `signalRuntimeModel` 상태(버퍼 크기 제한 있음).

## 구조 / 흐름 (확인된 코드 기준)

파일 위치: `frontend/src/page/signal/`

### 진입·레이아웃 — `SignalPage.jsx`, `TopBar.jsx`
- 데스크톱은 12컬럼 그리드: 상단 `TopBar`(심볼·시간범위·펀딩레이트·관리자 `ParamPanel`), 좌측 `LongPanel`/`ShortLiqPanel`, 중앙 `MainCore`(`TradingViewWidget`+`DivergenceBar`+`MiniChartPlaceholder`), 우측 `ShortPanel`/`LiquidationPanel`, 하단 전체폭 `PatternStrip`.
- 모바일은 `isMobile`로 수직 스택: TopBar → 에너지/TugOfWar 미니영역 → 패널 2×2 그리드.

### 데이터 로드 (REST) — `SignalPage.jsx`, `hooks/useSignalCandles.js`
- `/api/signal/init`(초기 상태), `/api/signal/params`(심볼별 `canEdit`·분석 파라미터), `/api/signal/history`(에너지·청산·OI 과거), `/api/signal/candles`·`/api/signal/candles/dates`(캔들·보유 날짜), `/api/analysis/templates`(분석 템플릿).

### 실시간 스트리밍 (WS + SSE)
- **캔들 WS**: `/ws/candle/{interval}?symbol={symbol}`. `CandleChart`·`StealthCaseViewer`가 인터벌별로 연결. 메시지 `is_closed:false`면 진행봉 `series.update`, `true`면 봉 마감 콜백(`onCandleUpdate`/`onCandleTime`).
- **시그널 SSE**: `useSignalSse`(→`fe-domain-binance`)가 주는 `aggTrades`/`forceOrders`/`latestOi`를 즉시 런타임 상태에 반영.

### 런타임 상태 — `model/signalRuntimeModel.js`
- `createSignalRuntimeState`(초기), `resetSignalRuntimeState`(심볼/시간범위 변경 시).
- `applyAggTrade`: 체결 방향으로 에너지 가감, 트레이드 버퍼 `TRADE_BUFFER_SIZE=20` 유지.
- `applyForceOrder`: 청산 `side`로 반대 포지션 에너지 차감 + 누계(`longLiqTotal`/`shortLiqTotal`), `LIQUIDATION_BUFFER_SIZE=50`.
- `appendOi`: `OI_BUFFER_SIZE=5000` 범위 누적. `appendCandle`: 캔들 히스토리 추가.

### 시각화 엔진 — `components/`
- `CandleChart`(lightweight-charts `CandlestickSeries`), `OiLineChart`(`AreaSeries`, rangeMs 슬라이싱), `EnergyGauge`(echarts gauge, jitter 애니), `TugOfWar`(keyframes 펄스). 툴팁 `subscribeCrosshairMove`(시간·종가·Delta / OI·가격).

### 패턴 탐지 — `engine/detectionEngine.js`, `engine/conditions.js`
- `detect`/`detectAB`가 캔들 배열을 순회. **Type A(스텔스 거래)** = `volumeMultiplierCondition`(직전 N봉 평균 거래량×배수 이상) ∩ `insideBarCondition`(직전 N봉 고저 범위 내). **Type B(스텔스 의심)** = `notAllDojisCondition` ∩ 거래량배수 ∩ `bodyRatioCondition`(직전 N봉 최소 몸통보다 작음). 결과는 `StealthCaseViewer`/`StealthSlot`이 `highlights`로 표시.

### 파라미터 동기화 — `ParamPanel.jsx`
- 슬라이더는 `setLocal`로 즉시 반영, '적용' 시 `handleParamsSave`→`apiClient.put`으로 서버 전송. 실패 시 `prevParamsRef`로 롤백.

## 연관 도메인
- 백엔드: `be-binance`(시그널 데이터·SSE), `be-analysis`(템플릿). 훅: `fe-domain-binance`(`useSignalSse`). 상세 관계는 `index.md`.
