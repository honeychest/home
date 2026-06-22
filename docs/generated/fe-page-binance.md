# 프론트 페이지: binance

> 이 문서는 로컬 LLM(gemma-4-26b-a4b-it-mlx)이 소스 코드를 근거로 자동 생성했다. 검증 전 초안이다.

## 역할 요약

**한 줄 정의** — Binance와 Upbit의 실시간 시세를 티커 카드로 나란히 비교해 보여주는 시세 페이지.

**누가·언제 쓰나** — 선택한 코인의 양 거래소 시세와 USDT 환율을 한눈에 비교하고 싶을 때.

**핵심 기능 3가지**
1. WebSocket 실시간 시세 수신
2. 코인/마켓 선택
3. PC·모바일 최적화 티커 카드 + 지갑 정보 표시

---

## 목차
- 개요
- 페이지 구조 및 레이아웃 구성
- 실시간 데이터 스트리밍 (WebSocket) 연동
- 코인 및 마켓 선택 로직
- 티커 카드 데이터 시각화 및 상태 관리
- 지갑 정보 조회 및 표시
- 반응형 UI 및 테마 적용 전략

## 개요

이 페이지는 Binance와 Upbit 간의 가격 정보를 시각화하여 제공하는 인터페이스를 구축합니다. `BinancePage.jsx`는 전체 페이지의 레이아웃을 관리하며, 사용자가 선택한 심볼에 따라 실시간 시세 데이터와 지갑 정보를 화면에 구성합니다.

주요 기능 및 컴포넌트 구성은 다음과 같습니다:

*   **실시간 시세 정보 제공**: `useBinanceWebSocket.ts`를 통해 가져온 데이터와 `useUpbitWebSocket.ts`를 통해 가져온 데이터를 결합하여 시세 정보를 표시합니다. `BinanceTickerCard.jsx`는 선택된 코인에 대한 시세와 USDT 환율 정보를 포함하며, `BinanceTicker.tsx` 및 `BinanceTickerMobile.tsx`를 통해 PC와 모바일 환경에 최적화된 시세 정보를 출력합니다.
*   **코인 선택 및 상태 관리**: `BINANCE_MARKETS` 목록을 기반으로 사용자가 코인을 선택할 수 있는 탭 인터페이스를 제공합니다. `BinanceTickerCard.jsx` 내에서 `onSelectSymbol`을 통해 선택된 심볼이 변경되면, 이에 따라 시세 데이터와 Upbit 구독 코드가 업데이트됩니다.
*   **지갑 정보 표시**: `useBinanceWallet.js`를 통해 관리되는 계정 정보를 `BinanceWalletCard.jsx`와 그 내부의 `BinanceWallet.tsx`를 통해 사용자에게 전달합니다.
*   **상태 시각화**: `buildBinanceLiveStatus.js`를 통해 생성된 라이브 상태 정보를 바탕으로, 데이터의 실시간성을 나타내는 시각적 요소(점멸 효과 등)를 `BinanceTickerCard.jsx`에 적용합니다.

## 페이지 구조 및 레이아웃 구성

`BinancePage`는 `Layout` 컴포넌트를 최상위로 사용하여 페이지를 구성하며, 내부적으로 `BinancePageHeader`, `BinanceTickerCard`, `BinanceWalletCard` 세 가지 주요 UI 컴포넌트를 수직 방향(`flexDirection: 'column'`)으로 배치합니다. (`frontend/src/page/binance/BinancePage.jsx`)

각 컴포넌트의 구성 요소는 다음과 같습니다:

*   **BinancePageHeader**: 페이지 상단에 위치하며, `getBinanceExchangePairLabel` 함수를 통해 가져온 레이블을 사용하여 거래소 간의 관계(예: Binance × Upbit)를 표시합니다. (`frontend/src/page/binance/ui/BinancePageHeader.jsx`, `frontend/src/page/binance/model/binancePageView.js`)
*   **BinanceTickerCard**: 페이지 중앙의 핵심 영역으로, 상단에는 `BINANCE_MARKETS` 데이터를 기반으로 한 코인 선택 탭과 실시간 상태 정보(`liveStatus`), USDT 환율 정보가 포함된 헤더 행이 위치합니다. 하단에는 `tickerWrapperRef`와 `minimumSizeStyle`이 적용된 영역 내에 PC용(`BinanceTicker`)과 모바일용(`BinanceTickerMobile`) 티커 컴포넌트가 각각 별도의 클래스(`pcOnly`, `mobileOnly`)를 통해 조건부로 렌더링됩니다. (`frontend/src/page/binance/ui/BinanceTickerCard.jsx`)
*   **BinanceWalletCard**: 페이지 하단에 위치하며, `BinanceWallet` 컴포넌트를 포함하는 카드 형태의 레이아웃입니다. (`frontend/src/page/binance/ui/BinanceWalletCard.jsx`, `frontend/src/page/binance/ui/BinanceWalletCard.jsx`)

전체적인 레이아웃은 `isMobile` 상태에 따라 패딩 값이 16px 또는 32px로 가변 적용되며, 콘텐츠의 최대 너비는 모바일 환경에서는 100%, 그 외에는 1120px로 제한됩니다. (`frontend/src/page/binance/BinancePage.jsx`)

## 실시간 데이터 스트리밍 (WebSocket) 연동

`BinancePage` 컴포넌트는 `useBinanceWebSocket` 훅을 호출하여 선택된 심볼(`selectedSymbol`)에 대한 실시간 `ticker` 데이터와 연결 상태인 `status`를 가져옵니다. (`frontend/src/page/binance/BinancePage.jsx`)

가져온 `ticker` 데이터는 `useTickerPanelStability` 훅에 전달되어 UI의 안정성을 위한 `tickerWrapperRef`와 `minimumSizeStyle`을 생성하는 데 사용됩니다. (`frontend/src/page/binance/BinancePage.jsx`)

또한, `useUpbitWebSocket` 훅은 선택된 코인(`selectedCoin`)에 따라 생성된 `upbitCodes`를 인자로 받아 실시간 업비트 티커 데이터를 가져옵니다. (`frontend/src/page/binance/BinancePage.jsx`)

수집된 `status`와 `ticker` 데이터는 `buildBinanceLiveStatus` 함수를 통해 실시간 상태 객체인 `liveStatus`로 변환되며, 이는 `BinanceTickerCard` 컴포넌트의 상태 표시(점의 색상, 배경색, 텍스트 등)에 반영됩니다. (`frontend/src/page/binance/BinancePage.jsx`, `frontend/src/page/binance/ui/BinanceTickerCard.jsx`)

최종적으로 `BinanceTicker` 및 `BinanceTickerMobile` 컴포넌트는 `ticker`, `upbitTicker`, `usdtKrwTicker` 데이터를 전달받아 실시간 시세 정보를 화면에 출력합니다. (`frontend/src/page/binance/ui/BinanceTickerCard.jsx`)

## 코인 및 마켓 선택 로직

`BINANCE_MARKETS` 데이터에 정의된 코인 목록을 기반으로 `BinancePage.jsx`에서 `selectedSymbol` 상태를 통해 현재 선택된 심볼을 관리합니다. 사용자가 `BinanceTickerCard.jsx` 내의 코인 탭 버튼을 클릭하면 `onSelectSymbol` 콜백이 호출되어 `selectedSymbol` 값이 업데이트됩니다.

선택된 심볼에 따른 상세 정보는 `getSelectedBinanceMarket(selectedSymbol)` 호출을 통해 `selectedCoin` 객체로 추출됩니다 (`frontend/src/page/binance/BinancePage.jsx`). 이 `selectedCoin`은 해당 코인의 업비트 코드(`upbitCode`)를 포함할 수 있으며, `useMemo`를 통해 계산된 `upbitCodes`는 `useUpbitWebSocket.ts`에 전달되어 관련 티커 데이터를 가져오는 데 사용됩니다 (`frontend/src/page/binance/BinancePage.jsx`). 

`BinanceTickerCard.jsx`에서는 `BINANCE_MARKets.map`을 통해 모든 코인 탭을 렌더링하며, 각 탭의 활성화 상태는 `coin.symbol === selectedSymbol` 비교를 통해 결정되고 `getCoinTabTone` 함수를 통해 스타일이 적용됩니다 (`frontend/src/page/binance/ui/BinanceTickerCard.jsx`, `frontend/src/page/binance/model/binanceTickerCardStyles.js`).

*(참고: 원문 소스 내 `BINANCE_MARKETS`는 `../../domain/binance/model/market/binanceMarketSelection.js`에서 임포트됨)*

## 티커 카드 데이터 시각화 및 상태 관리

`BinancePage`에서 관리되는 `selectedSymbol` 상태에 따라 `BINANCE_MARKETS` 목록을 순회하며 코인 탭을 생성합니다. 각 탭은 `selectedSymbol`과 해당 코인의 `symbol`을 비교하여 활성화 여부를 결정하며, `getCoinTabTone` 함수를 통해 활성 상태에 따른 스타일(border, background, color, outline)을 적용합니다. (`frontend/src/page/binance/BinancePage.jsx`, `frontend/src/page/binance/ui/BinanceTickerCard.jsx`, `frontend/src/page/binance/model/binanceTickerCardStyles.js`)

실시간 상태 시각화를 위해 `buildBinanceLiveStatus`를 통해 생성된 `liveStatus` 객체를 사용합니다. 이 객체의 `color`, `fill`, `transition`, `blink` 속성을 활용하여 상태 표시 점(liveDot)의 색상, 배경색, 애니메이션 효과 및 텍스트를 제어합니다. (`frontend/src/page/binance/BinancePage.jsx`, `frontend/src/page/binance/ui/BinanceTickerCard.jsx`)

티커 데이터는 화면 크기에 따라 `BinanceTicker`(PC용) 또는 `BinanceTickerMobile`(모바일용) 컴포넌트로 분기되어 시각화됩니다. 이때 `selectedCoin`의 `upbitCode` 존재 여부에 따라 `upbitTicker`가 선택적으로 전달되며, `usdtTicker`와 함께 페어 정보(`pairLabel`)를 포함하여 출력됩니다. (`frontend/src/page/binance/ui/BinanceTickerCard.jsx`)

USDT 환율 정보는 `formatUsdtRateLabel` 함수를 통해 처리됩니다. 전달받은 `usdtTicker` 데이터가 존재할 경우 '1 USDT = ₩[금액]' 형식으로, 없을 경우 '1 USDT = ...' 형식으로 레이블을 생성합니다. (`frontend/src/page/binance/ui/BinanceTickerCard.jsx`, `frontend/src/page/binance/model/binanceTickerCardView.js`)

## 지갑 정보 조회 및 표시

`BinancePage` 컴포넌트에서 `useBinanceWallet` 훅을 호출하여 지갑 관련 데이터인 `accountInfo`, `walletLoading`, `walletError`, `serverError`를 가져옵니다. (`frontend/src/page/binance/BinancePage.jsx`)

만약 `serverError`가 존재할 경우 `ErrorPage`를 반환하며, `walletLoading` 상태가 true인 경우에는 아무것도 렌더링하지 않습니다. (`frontend/src/page/binance/BinancePage.jsx`)

지갑 정보는 `BinanceWalletCard` 컴포넌트를 통해 표시됩니다. 이 컴포넌트는 `accountInfo`, `walletLoading`, `walletError`를 인자로 전달받아 내부의 `BinanceWallet` 컴포넌트에 다시 전달합니다. (`frontend/src/page/binance/ui/BinanceWalletCard.jsx`, `frontend/src/page/binance/BinancePage.jsx`)

## 반응형 UI 및 테마 적용 전략

`BinancePage` 컴포넌트 내에서 `window.innerWidth`를 기준으로 768px 미만 여부를 판단하여 모바일 환경을 감지하며, `resize` 이벤트 리스너를 통해 창 크기 변화에 대응합니다. (`frontend/src/page/binance/BinancePage.jsx`)

UI 레이아웃은 `isMobile` 상태에 따라 패딩 값(`16px` 또는 `32px`)과 컨테이너의 최대 너비(`maxWidth`)를 동적으로 변경하여 적용합니다. (`frontend/src/page/binance/BinancePage.jsx`)

`BinanceTickerCard` 컴포넌트는 PC용 UI인 `BinanceTicker`와 모바일용 UI인 `BinanceTickerMobile`을 각각 별도의 클래스(`pcOnly`, `mobileOnly`)로 분리하여 제공함으로써 기기 환경에 적합한 컴포넌트를 렌더링합니다. (`frontend/src/page/binance/ui/BinanceTickerCard.jsx`)

테마 적용을 위해 `usePageTheme` 훅을 사용하여 테마 정보를 가져오며, 추출된 테마 값에 따라 `theme-` 접두사가 붙은 클래스명을 생성하여 적용합니다. (`frontend/src/page/binance/BinancePage.jsx`)
