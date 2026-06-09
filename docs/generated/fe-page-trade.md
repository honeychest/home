# 프론트 페이지: trade

> 이 문서는 로컬 LLM(gemma-4-26b-a4b-it-mlx)이 소스 코드를 근거로 자동 생성했다. 검증 전 초안이다.

## 목차
- 개요
- 실시간 데이터 수신 및 틱 누적 처리
- 체결 내역 하이라이트 및 시각화 로직
- 데스크탑/모바일 UI 렌더링 전략
- 체결 내역 조회 및 필터링 시스템
- 임계값 관리 및 권한 정책
- 데이터 포맷팅 및 표시 유틸리티

## 개요

이 도메인은 실시간 BTC 체결 데이터를 시각화하고 관리하는 기능을 제공합니다. 주요 기능은 다음과 같습니다.

실시간 체결 데이터(SSE)를 통해 수신되는 거래 내역을 데스크탑 환경에서는 테이블 형태로, 모바일 환경에서는 카드 리스트 형태로 출력합니다. 데스크탑은 큰 거래 내역과 실시간 틱(Tick) 정보를 분리하여 보여주며, 모바일은 무한 스크롤을 통해 추가 데이터를 로드합니다.
`frontend/src/page/trade/TradePage.jsx`, `frontend/src/page/trade/TickTable.jsx`, `frontend/src/page/trade/TradeDesktopTradesTable.jsx`, `frontend/src/page/trade/TradeMobileList.jsx`, `frontend/src/page/trade/model/hook/useTradeMobileLoadMore.js`

특정 금액 이상의 대형 체결을 감지하기 위한 스캔 슬롯(Scan Slot) 애니메이션과 임계값(Threshold) 관리 기능을 포함합니다. 사용자는 사이드 패널을 통해 심볼, 시장 타입, 날짜 범위, 정렬 기준 등을 설정하여 과거 체결 내역을 조회할 수 있습니다.
`frontend/src/page/trade/TradeScanSlot.jsx`, `frontend/src/page/trade/TradePanel.tsx`, `frontend/src/page/trade/model/hook/useTradePanelSearch.ts`

새로운 체결이 발생했을 때 상단 거래를 강조(Highlight)하는 기능과, 실시간 틱 데이터를 누적하여 매수/매도 총량을 계산하는 기능을 제공합니다.
`frontend/src/page/trade/model/hook/useNewTradeHighlight.js`, `frontend/src/page/trade/model/hook/useTickTotals.js`

## 실시간 데이터 수신 및 틱 누적 처리

실시간 틱 데이터는 `useRawTickSse.ts`를 통해 수신되며, 이를 기반으로 누적된 매수/매도 거래량을 계산하기 위해 `useTickTotals.js` 훅이 사용됩니다. `useTickTotals.js`는 수신된 `ticks`와 SSE 재연결 상태인 `isReconnecting`을 인자로 받아 처리합니다.

데이터 누적 로직은 `tickAccumulation.js`의 `reduceTickState` 함수를 통해 수행됩니다. 이 함수는 다음과 같은 규칙을 따릅니다:
- `isReconnecting`이 true인 경우, `initialTickState()`를 호출하여 누적된 모든 상태(totals 및 prevTicks)를 초기화합니다.
- 새로운 `ticks`가 들어오면 기존에 저장된 `prevTicks`와 비교하여 새로 추가된 객체만을 식별합니다.
- 식별된 새로운 틱 중 `isBuyerMaker`가 true인 경우 매도(sell)로, false인 경우 매수(buy)로 분류하여 각 델타값을 계산합니다. 이때 수량이 유효하지 않은(NaN, 비유한수, 0 이하) 데이터는 제외됩니다.
- 계산된 델타값은 기존 `state.totals`에 합산되어 새로운 상태로 반환됩니다.

최종적으로 계산된 누적량은 `useTickTotals.js` 내의 `setTotals`를 통해 상태로 관리되며, UI에서는 `formatTickQtyTotal` 함수(`tradeDisplayModel.js`)를 통해 소수점 4자리까지 포맷팅되어 표시됩니다.

## 체결 내역 하이라이트 및 시각화 로직

새로운 체결 데이터가 유입될 때 최상단 항목을 시각적으로 강조하기 위해 `useNewTradeHighlight.js` 훅을 사용합니다. 이 훅은 `detectNewFirstId` 함수를 통해 이전의 첫 번째 체결 ID(`prevFirstIdRef.current`)와 현재 전달된 `firstId`를 비교하며, ID가 변경되었을 경우에만 해당 ID를 새로운 항목으로 감지합니다(`newTradeHighlight.js`). 감지된 ID는 `newIds` 상태에 추가되며, 설정된 `HIGHLIGHT_DURATION_MS`(500ms)가 경과하면 자동으로 상태에서 제거됩니다.

데스크탑 환경의 `TradeDesktopTradesTable.jsx`에서는 `newTradeIds.has(trade.id)`를 통해 해당 체결 건이 새로운 항목인지 확인합니다. 만약 새로운 항목으로 판별되면, 기존의 데이터 대신 `Skeleton` 컴포넌트를 사용하여 스켈레톤 애니메이션 효과를 렌더링함으로써 시각적 변화를 제공합니다(`TradeDesktopTradesTable.jsx`).

모바일 환경의 `TradeMobileList.jsx`에서도 동일하게 `newIds.has(trade.id)`를 사용하여 새로운 항목을 식별하며, `styles.newRow` 클래스를 적용하여 시각적 차이를 둡니다(`TradeMobileList.jsx`).

## 데스크탑/모바일 UI 렌더링 전략

데스크탑 환경에서는 화면을 2열 구조로 분할하여 정보를 제공한다. 좌측 영역에는 `TradeDesktopTradesTable.jsx`를 통해 체결 내역을 테이블 형태로 렌더링하며, 상단에는 `TradeScanSlot.jsx`를 배치하여 스캔 상태와 임계값을 표시한다. 우측 영역에는 `TickTable.jsx`를 배치하여 실시간 틱 데이터를 시각화한다. 반면 모바일 환경에서는 `TradeMobileList.jsx`를 사용하여 카드 형태의 목록 구조로 렌더링하며, `useTradeMobileLoadMore.js`를 통해 무한 스크롤 방식의 데이터 로딩을 수행한다.

데이터 시각화 측면에서, 새로운 체결 건이 발생하면 `useNewTradeHighlight.js`를 통해 생성된 ID를 기반으로 `styles.newRow` 클래스를 적용하여 스켈레톤 애니메이션 효과를 제공한다. 데스크탑의 `TradeDesktopTradesTable.jsx`와 모바일의 `TradeMobileList.jsx` 모두 이 하이라이트 로직을 공유하여 사용자에게 새로운 데이터임을 알린다. 또한, `useTickTotals.js`를 통해 계산된 누적 거래량은 데스크탑의 틱 테이블 상단에 표시된다.

## 체결 내역 조회 및 필터링 시스템

`useTradePanelSearch.ts` 훅을 통해 심볼, 시장 타입(SPOT/FUTURES), 날짜 범위(시작일/종료일), 정렬 기준, 정렬 순서, 페이지 크기 등 다양한 검색 조건을 관리합니다. 사용자가 `handleSearch`를 호출하면 설정된 파라미터를 포함하여 `/api/binance/trades` 엔드포인트로 GET 요청을 보내며, 결과 데이터는 `PageResult` 인터페이스 구조로 반환됩니다.

조회 결과는 `TradePanel.tsx` 내의 테이블을 통해 시각화됩니다. 각 행은 시장 타입, 가격, 금액(USD), 경과 시간 정보를 포함하며, `isBuyerMaker` 값에 따라 매수/매도 방향이 구분되어 표시됩니다. 결과 데이터의 총 개수와 현재 페이지 범위는 `startItem` 및 `endItem` 계산 로직을 통해 사용자에게 제공됩니다.

검색 결과의 페이지네이션은 `fetchPage` 메서드를 통해 구현되며, 사용자는 이전/다음 버튼을 사용하여 페이지를 이동할 수 있습니다. 또한 `handleSizeChange`를 통해 한 페이지에 표시할 데이터 건수를 변경할 수 있으며, 이 경우 페이지 번호는 0으로 초기화됩니다.

임계값(Threshold) 설정 기능은 `useTradeThreshold.js`와 `TradePanel.tsx`를 통해 관리됩니다. 서버로부터 가져온 임계값은 `mapThresholdResponse`를 통해 UI 상태로 변환되며, 관리자 권한(`hasAdminAccess`)이 있는 경우에만 `composeCanEdit` 로직에 의해 편집 권한이 부여됩니다. 사용자가 새로운 임계값을 입력하고 적용하면 `/api/binance/trades/threshold`로 POST 요청이 전송됩니다.

## 임계값 관리 및 권한 정책

임계값 데이터는 `apiClient.get('/api/binance/trades/threshold')`를 통해 서버로부터 가져오며, `mapThresholdResponse` 함수를 통해 UI 상태로 매핑됩니다. 이때 서버에서 전달된 `canEdit` 플래그와 클라이언트의 관리자 권한(`hasAdminAccess`)을 `composeCanEdit` 함수로 결합하여 최종적인 편집 가능 여부를 결정합니다.

사용자가 `TradePanel`에서 새로운 임계값을 입력하고 적용할 경우, `apilet.post('/api/binance/trades/threshold?value={value}')`를 호출하여 서버에 반영합니다. 성공 시 `onThresholdChange` 콜백을 통해 상태가 업데이트됩니다.

임계값 입력 시에는 `1` 초과 `10,000,000` 이하의 정수 값만 허용하며, `e`, `E`, `+`, `-`, `.` 등의 문자는 입력되지 않도록 제어됩니다. 최종적으로 결정된 임계값은 `formatThreshold` 함수를 통해 UI에 특정 형식으로 표시됩니다.

## 데이터 포맷팅 및 표시 유틸리티

`tradeDisplayModel.js`에 정의된 순수 함수들을 통해 데이터의 시각적 표현을 관리합니다.

* **금액 및 수량 포맷팅**:
    * `formatPrice`: 숫자를 소수점 둘째 자리까지 표시하며, 유효하지 않은 값은 '—'로 반환합니다. (`tradeDisplayModel.js`)
    * `formatQty`: 숫자를 소수점 4자리까지 고정하여 표시하며, 값이 없으면 '—'를 반환합니다. (`tradeDisplayModel.js`)
    * `formatValue`: 금액을 $ 단위로 변환합니다. 1,000,000 이상일 경우 'M' 단위로, 1,000 이상일 경우 'K' 단위로 표시합니다. (`tradeDisplayModel.js`)
    * `formatKrw`: USD 금액에 `USD_KRW_RATE`(1450)를 곱하여 원화 단위로 변환하고 콤마를 포함해 표시합니다. (`tradeDisplayModel.js`)
    * `formatTickQtyTotal`: 틱 데이터의 수량을 소수점 4자리까지 표시하며, 값이 없으면 '0.0000'을 반환합니다. (`tradedisplayModel.js`)

* **시간 및 경과 시간 표시**:
    * `formatTime`: `tradedAt` 값을 기반으로 한국 표준시(Asia/Seoul) 기준 24시간 형식의 시:분:초를 반환합니다. (`tradedisplayModel.js`)
    * `getElapsed`: 현재 시간과 체결 시각의 차이를 계산하여 '방금', 'n분 전', 'n시간 전', 'n일 전' 형태로 반환합니다. (`tradedisplayModel.js`)

* **임계값 표시**:
    * `formatThreshold`: 임계값(v)을 '금액 / 절반 금액 USD' 형태로 포맷팅합니다. (`tradedisplayModel.js`)

* **기타 유틸리티**:
    * `commaInt`: 숫자에 천 단위 구분 콤마를 추가합니다. (`tradedisplayModel.js`)
