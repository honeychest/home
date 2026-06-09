# 프론트 도메인: binance

> 이 문서는 로컬 LLM(gemma-4-26b-a4b-it-mlx)이 소스 코드를 근거로 자동 생성했다. 검증 전 초안이다.

## 목차
- 개요
- 실시간 시세 데이터 수집 및 관리 (WebSocket/SSE)
- 거래 내역 및 틱 데이터 스트리밍 처리
- 지갑 잔고 조회 및 상태 관리 (REST API)
- 시세 데이터 모델링 및 UI 표시 로직
- 반응형 레이아웃 및 스켈레톤 처리 (Desktop/Mobile)
- 연결 안정성 및 재연결 정책 (Visibility API/Error Handling)

## 개요

이 도메인은 바이낸스(Binance)의 실시간 시세 데이터와 사용자 지갑 잔고를 관리하고 화면에 표시하기 위한 프론트엔드 로직을 포함합니다.

실시간 시세 데이터는 WebSocket(`useBinanceWebSocket.ts`)과 SSE(`useBinanceTradeSse.ts`, `useRawTickSse.ts`, `useSignalSse.ts`)를 통해 수신됩니다. WebSocket은 심볼별 24시간 롤링 통계(`BinanceTicker`)를 제공하며, SSE는 실시간 체결 내역, 틱 데이터, 그리고 시그널 대시보드용 데이터(`aggtrade`, `forceOrder`, `oi`)를 스트리밍합니다. 또한 업비트(Upbit)와의 가격 비교 및 프리미엄 계산을 위해 업비트 WebSocket(`useUpbitWebSocket.ts`)을 통해 KRW 시장 데이터를 병행하여 수신합니다.

수신된 데이터는 `buildBinanceTickerDisplayModel`(`binanceTickerDisplayModel.js`)을 통해 UI에 적합한 모델로 변환됩니다. 이 과정에서 바이낸스 현재가와 업비트 가격을 이용한 프리미엄 계산, 환율 기준 KRW 변환 등이 수행됩니다.

지갑 기능은 REST API(`apiClient.js`)를 통해 계좌 정보를 조회하며, `useBinanceWallet.js`가 상태 관리를 담당합니다. 수집된 잔고 데이터는 `BinanceWallet.tsx`를 통해 사용자에게 시각화됩니다.

UI 레이아웃은 데스크톱용(`BinanceTicker.tsx`)과 모바일용(`BinanceTickerMobile.tsx`)으로 분리되어 있으며, 데이터 로딩 중에는 스켈레톤 UI(`TickerSkeletonBox.tsx`)를 통해 사용자 경험을 유지합니다. 모든 데이터는 `binanceMarketSelection.js`에 정의된 시장 정보와 연동되어 관리됩니다.

## 실시간 시세 데이터 수집 및 관리 (WebSocket/SSE)

실시간 시세 데이터는 WebSocket과 SSE(Server-Sent Events)를 통해 수집되며, 각 목적에 따라 서로 다른 훅과 프로토콜을 사용하여 관리됩니다.

**1. WebSocket을 이용한 실시간 시세 수집**
바이낸스의 24시간 롤링 통계 데이터를 수신하기 위해 `useBinanceWebSocket.ts`를 사용합니다. 이 훅은 선택된 심볼(`selectedSymbol`)을 쿼리 파라미터로 전달하여 서버에 연결하며, `onmessage` 이벤트를 통해 수신된 JSON 데이터를 `BinanceTicker` 인터페이스 타입으로 파싱하여 상태를 업데이트합니다. 또한, 탭 비활성화 시 네트워크 자원 절약을 위해 `visibilitychange` 이벤트를 감지하여 연결을 종료하고, 다시 활성화될 때 재연결하는 로직을 포함합니다. 업비트 KRW 티커 데이터는 `useUpbitWebSocket.ts`를 통해 별도의 서버 중계 경로(`/ws/upbit-price`)로 수집되며, `parseUpbitPayload` 함수를 통해 다양한 바이너리 및 문자열 데이터 타입을 안전하게 처리합니다.

**2. SSE를 이용한 실시간 체결 및 시그널 데이터 수집**
체결 내역과 특수 이벤트 데이터는 SSE를 통해 스트리밍됩니다.
*   **체결 내역(Trades):** `useBinanceTradeSse.ts`는 `/api/binance/trades/sse`를 통해 실시간 체결 정보를 수신합니다. 초기 로드 시 `loadRecent`를 통해 최근 100건을 먼저 가져오며, 이후 발생하는 `trade` 이벤트는 `seenIds`를 통해 중복을 방지하며 목록에 삽입됩니다.
*   **실시간 틱(Raw Ticks):** `useRawTickSse.ts`는 `/api/binance/trades/tick-sse`를 통해 틱 데이터를 수집합니다. 데이터 부하를 줄이기 위해 `BATCH_MS`(100ms) 간격으로 큐(`queueRef`)에 쌓인 데이터를 배치(Batch) 단위로 처리하여 UI를 업데이트합니다.
*   **시그널 데이터(AggTrade, ForceOrder, OI):** `useSignalSse.ts`는 `/api/signal/stream/sse?symbol=${symbol}`를 통해 종합적인 시그널 데이터를 수집합니다. `aggtrade`, `forceOrder`, `oi` 등 각기 다른 이벤트 타입에 따라 데이터를 분류하여 저장하며, 심볼 변경 시 `SYMBOL_CHANGE_DEBOUNCE_MS`(300ms)만큼의 디바운스 시간을 두어 안정적인 재연결을 보장합니다.

## 거래 내역 및 틱 데이터 스트리밍 처리

실시간 거래 데이터와 틱(Tick) 데이터는 각각 SSE(Server-Sent Events)를 통해 스트리밍되며, 데이터의 특성에 따라 서로 다른 처리 로직을 적용한다.

**1. 실시간 거래 내역(Trades) 스트리밍**
`useBinanceTradeSse.ts` 훅은 `/api/binance/trades/sse` 엔드포인트를 통해 실시간 체결 정보를 수신한다.
*   **데이터 관리**: 수신된 `TradeEntry` 데이터는 `seenIds`를 통해 중복을 방지하며, 초기 로드 시 `/api/binance/trades/recent?limit=100`을 통해 과거 데이터를 가져와 병합한다.
*   **애니메이션 및 상태 제어**: 새로운 체결 발생 시 `scanState`를 `expanding`으로 변경하여 애니메이션 효과를 부여하며, 애니메이션 중에는 즉시 삽입되도록 처리한다. 모바일 환경에서는 무한 스크롤을 위해 `loadMore` 함수를 통해 `beforeId` 기반의 추가 로드를 지원한다.
*   **연결 관리**: 브라우저 탭의 가시성 변화(`visibilitychange`)나 페이지 복원(`pageshow`) 시 재연결 로직을 수행하여 연결성을 유지한다.

**2. 실시간 틱(Tick) 데이터 스트리밍**
`useRawTickSse.ts` 훅은 `/api/binance/trades/tick-sse`를 통해 초고속으로 발생하는 틱 데이터를 처리한다.
*   **배치(Batch) 처리**: 데이터의 빈도가 매우 높으므로, `queueRef`에 데이터를 즉시 쌓아두었다가 `BATCH_MS`(100ms) 간격의 인터벌을 통해 한꺼번에 상태를 업데이트하는 배치 방식을 사용한다. 이는 잦은 리렌더링으로 인한 성능 저하를 방지하기 위함이다.
*   **데이터 캡처 및 제한**: `TICKS_MAX`(100건)로 표시되는 데이터 개수를 유지하며, 큐의 최대 크기는 `QUEUE_MAX`(1000건)로 관리한다.
*   **재연결 정책**: 탭이 숨겨진 상태에서 일정 시간(`OUT_DELAY_MS`, 180,000ms)이 경과한 후 다시 활성화될 경우, 데이터 정합성을 위해 기존 큐를 초기화하고 재연결을 수행한다.

**3. 시그널(Signal) 데이터 스트리밍**
`useSignalSse.ts` 훅은 `/api/signal/stream/sse?symbol=${symbol}`를 통해 특정 심볼에 대한 복합적인 시그널 데이터를 수신한다.
*   **이벤트별 데이터 분리**: 하나의 SSE 연결 내에서 `aggtrade`(집계 체결), `forceOrder`(강제 청산), `oi`(미결제약정) 등 서로 다른 타입의 이벤트를 구분하여 수신한다.
*   **상태 관리**: `aggTrades`는 최근 100건, `forceOrders`는 최근 50건으로 제한하여 상태를 유지하며, 심볼 변경 시 `SYMBOL_CHANGE_DEBOUNCE_MS`(300ms)의 디바운스를 적용하여 불필요한 재연결을 방지한다.

## 지갑 잔고 조회 및 상태 관리 (REST API)

바이낸스 지갑 잔고는 실시간성이 낮은 특성을 고려하여 WebSocket이 아닌 REST API를 통해 1회성으로 조회합니다. `useBinanceWallet.js`의 `useBinanceWallet` 훅은 컴포넌트 마운트 시 `apiClient.get('/api/binance/account')`를 호출하여 계좌 정보를 가져옵니다.

조회 결과는 `binanceWalletLoadPolicy.js`의 `classifyWalletResponse`를 통해 성공 여부가 판단되며, 데이터는 `binanceWalletState.js`의 `applyWalletOutcome`을 거쳐 상태에 반영됩니다. 성공 시 `accountInfo`에 데이터가 저장되고 `walletLoading`이 `false`로 변경됩니다. 에러 발생 시에는 `classifyWalletError`를 통해 분류된 결과가 상태에 적용됩니다.

`BinanceWallet.tsx` 컴포넌트는 전달받은 `accountInfo`를 바탕으로 잔고 목록을 렌더링합니다. 이때 `binanceWalletState.js`에서 생성된 초기 상태를 기반으로 로딩, 에러, 데이터 없음 상황을 각각 처리합니다. 또한 `binanceWalletState.js`에 정의된 구조를 활용하여, `free`(가용 수량) 또는 `locked`(주문 묶인 수량) 중 하나라도 0보다 큰 유의미한 잔고를 가진 코인만을 필터링하여 화면에 표시합니다.

## 시세 데이터 모델링 및 UI 표시 로직

바이낸스 WebSocket을 통해 수신된 `BinanceTicker` 데이터는 24시간 통계 정보를 포함하며, 모든 가격 필드는 정밀도 유지를 위해 문자열(string) 타입으로 제공됩니다. `useBinanceWebSocket.ts`에서 수신된 이 데이터는 `BinanceTicker` 컴포넌트와 `BinanceTickerMobile.tsx`로 전달되어 실시간 시세 표시의 핵심 근거로 사용됩니다.

UI 표시를 위한 데이터 가공은 `binanceTickerDisplayModel.js`의 `buildBinanceTickerDisplayModel` 함수를 통해 수행됩니다. 이 함수는 바이낸스 데이터(`ticker`), 업비트 데이터(`upbitTicker`), 그리고 USDT/KRW 환율 데이터(`usdtKrwTicker`)를 결합하여 다음과 같은 계산 로직을 수행합니다:

*   **가격 및 변동률**: `ticker.c`(현재가), `ticker.h`(고가), `l`(저가), `P`(변동률)를 `parseFloat`로 변환하여 사용합니다. `highDiffFromCurrent`와 `lowDiffFromCurrent`를 통해 현재가 대비 고가/저가의 차이값을 계산합니다.
*   **환율 기준 KRW 계산**: `hasUsdtRate` 여부를 확인하여 `currentPrice * usdtKrwTicker.trade_price`를 통해 환율이 적용된 KRW 가격(`calcKrw`)을 산출합니다.
*   **프리미엄 계산**: 업비트 데이터가 존재하고 `calcKrw`가 계산된 경우, `upbitTradePrice - calcKrw`를 통해 프리미엄(`premium`)을 구하며, 이를 바탕으로 `premiumRate`(프리미엄 비율)를 계산합니다.
*   **색상 및 기호 결정**: 변동률(`changeRate`)의 양수/음수 여부에 따라 `color`와 `sign`을 결정하며, 프리미엄의 경우 별도의 `premiumColor`와 `premiumSign`을 설정합니다.

계산된 모델은 각 UI 환경에 맞춰 다르게 렌더링됩니다. `BinanceTicker.tsx`는 데스크톱 환경을 위해 3열 그리드 레이아웃을 사용하여 현재가, 프리미엄, 환율 기준 KRW 정보를 배치하며, `BinanceTickerMobile.tsx`는 모바일 환경에 최적화된 세로 스택 레이아웃을 사용하여 고가/현재가/저가 순으로 정보를 배치합니다. 데이터 로딩 중에는 `binanceTickerDisplayModel.js`에서 정의된 포맷팅 함수들과 함께 `TickerSkeletonBox.tsx`를 활용한 스켈레톤 UI가 표시되어 레이아웃 이동(Layout Shift)을 방지합니다.

## 반응형 레이아웃 및 스켈레톤 처리 (Desktop/Mobile)

데스크톱 환경에서는 `BinanceTicker.tsx`를 사용하며, 3열 그리드 레이아웃을 통해 정보를 표시합니다. 모바일 환경(768px 이하)에서는 `BinanceTickerMobile.tsx`가 렌더링되며, 세로 스택 레이아웃을 통해 정보를 재배치합니다. 두 컴포넌트 모두 데이터가 없는 상태(예: 심볼 변경 시 `ticker`가 `null`인 경우)에서는 각 환경에 최적화된 스켈레톤 UI를 표시하여 레이아웃 이동(Layout Shift)을 방지합니다.

데스크톱용 스켈레톤은 `BinanceTickerSkeleton` 함수를 통해 구현되며, 실제 렌더링 구조와 동일한 고가/현재가/저가 라인 및 정보 그리드 레이아웃을 유지합니다 (`BinanceTicker.tsx`). 모바일용 스켈레톤은 `BinanceTickerMobileSkeleton` 함수를 통해 구현되며, 세로형 구조에 맞춰 각 섹션(고가/현재가/저가, 프리미엄, KRW 2열, InfoBox)의 높이와 배치를 구성합니다 (`BinanceTickerMobile.tsx`).

스켈레톤의 애니메이션 효과는 `TickerSkeletonBox.tsx`에서 관리됩니다. `getTickerSkeletonAnimationName` 함수를 통해 데스크톱용(`shimmer`)과 모바일용(`shimmerMobile`) 애니메이션 이름을 구분하여 적용하며, `buildTickerSkeletonKeyframes`를 통해 각 모드에 맞는 `@keyframes`를 주입합니다 (`frontend/src/domain/binance/ui/ticker/shared/tickerSkeleton.js`).

## 연결 안정성 및 재연결 정책 (Visibility API/Error Handling)

브라우저의 `visibilitychange` 이벤트와 `PageTransitionEvent`를 활용하여 탭 활성화 상태에 따른 재연결 정책을 수행합니다. `useBinanceWebSocket.ts`에서는 탭이 비활성화(`document.hidden: true`)될 경우 `isManualClose.current` 플래그를 `true`로 설정하고 기존 소켓을 명시적으로 닫아 자동 재연결을 방지하며, 다시 활성화될 때 `connect()`를 호출합니다. `useRawTickSse.ts`와 `useSignalSse.ts` 역시 `visibilitychange` 및 `pageshow` 이벤트를 감지하여 재연결 로직을 수행합니다. 특히 `useRawTickSse.ts`는 탭이 숨겨진 시점(`lastHiddenAtRef`)을 기록하여, 다시 활성화되었을 때 `OUT_DELAY_ms`(180,000ms) 이상 경과한 경우에만 `reconnectNow()`를 통해 재연결 및 초기화를 수행하는 차별화된 정책을 가집니다.

네트워크 오류나 서버 측 종료에 대비한 에러 핸들링은 각 훅의 `onerror` 및 `onclose` 이벤트에서 처리됩니다. `useBinanceWebSocket.ts`는 예기치 않은 종료 시 `reconnectTimerRef`를 사용하여 3초 후 재연결을 시도하며, `useBinanceTradeSse.ts`는 `RECONNECT_DELAY_MS`(1,000ms)의 지연 시간을 두고 재연결 및 데이터 로드를 수행합니다. `useSignalSse.ts`는 심볼 변경 시 `symbolDebounceTimerRef`를 통해 디바운싱을 적용하여 불필요한 연결 시도를 방지하며, `useUpbitWebSocket.ts`는 코드가 비어있거나 중복된 경우를 정규화하여 연결을 관리하고 예기치 않은 종료 시 3초 후 재연결을 시도합니다. 모든 훅은 컴포넌트 언마운트 시 `clearTimeout`과 `close()`를 호출하여 메모리 누수와 중복 연결을 방지하는 클린업 과정을 포함합니다.
