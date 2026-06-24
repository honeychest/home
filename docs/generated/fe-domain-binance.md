# 프론트 도메인: binance

> wiki-refresh로 실제 소스를 읽고 검증함(2026-06-24). 페이지 간 관계는 `index.md` 참고.

## 한 줄 요약
바이낸스 실시간 시세(WebSocket)와 체결·틱·시그널(SSE)을 수신하고, 업비트 KRW 시세와 비교해 김치 프리미엄까지 계산해 보여주는 프론트 도메인. 지갑 잔고는 REST로 1회 조회한다.

## 이런 걸 물을 때 찾으면 된다 (검색 키워드)
- "바이낸스 실시간 시세는 어떻게 받아와? WebSocket / SSE 차이"
- "김치 프리미엄 계산 / 업비트 가격 비교 / 환율 KRW 변환"
- "실시간 체결/틱 스트리밍 / 배치 처리 / 재연결"
- "시그널 SSE aggtrade forceOrder oi"
- "바이낸스 지갑 잔고 조회 / account API"
- "탭 비활성화 시 연결 끊김 / 재연결 정책"

## 핵심 개념·용어
- **WebSocket vs SSE**: 시세 티커(24h 통계)는 WebSocket(`/ws/binance-price`), 체결/틱/시그널은 단방향 SSE(`EventSource`).
- **24hrTicker(`BinanceTicker`)**: 바이낸스 24시간 롤링 통계. 모든 가격은 정밀도 보존을 위해 문자열(`c`현재가, `h`고가, `l`저가, `P`변동률 등).
- **김치 프리미엄(premium)**: `업비트 KRW 현재가 - (바이낸스 USDT가 × USDT/KRW 환율)`. 비율은 `premium / calcKrw × 100`.
- **배치 처리(틱)**: 틱은 초당 다수 발생 → 큐에 모았다가 `BATCH_MS`(100ms)마다 한꺼번에 렌더(리렌더 폭주 방지).
- **수동 종료 플래그(isManualClose)**: 탭 비활성화로 *의도적으로* 닫은 경우 자동 재연결을 막는 구분 플래그.

## 구조 / 흐름 (확인된 코드 기준)

파일 위치: `frontend/src/domain/binance/`

### 시장 정의 — `model/market/binanceMarketSelection.js`
- `BINANCE_MARKETS`: BTC/ETH/SOL/XRP 4종(`{symbol:'BTCUSDT', code:'BTC', label, upbitCode:'KRW-BTC'}` 형태).
- `getSelectedBinanceMarket(symbol)`(없으면 첫 번째), `getUpbitSubscriptionCodes(market)` → `[market.upbitCode, 'KRW-USDT']`(환율 계산용 USDT 포함).

### 시세 WebSocket — `model/hook/useBinanceWebSocket.ts`
- `wss?/ws://{host}/ws/binance-price?symbol=${selectedSymbol}` 연결(Vite 프록시 경유). 수신 JSON을 `BinanceTicker`로 파싱, 심볼 불일치는 무시.
- 상태 `WsStatus`: `connecting|connected|disconnected`. 비정상 종료 시 3초 후 재연결. `visibilitychange`로 탭 숨김 시 수동 종료, 복귀 시 재연결. `effectSeq` 가드로 StrictMode 중복 연결 방지.

### 업비트 WebSocket — `model/hook/useUpbitWebSocket.ts`
- `/ws/upbit-price?codes=${encoded}`(서버 중계). `parseUpbitPayload`로 string/ArrayBuffer/Blob 안전 파싱, `type==='ticker'`이고 요청 코드에 속한 것만 `tickers[code]`(trade_price 등) 갱신. 3초 재연결, 탭 처리 동일. codes 비면 연결 안 함.

### 체결 SSE — `model/hook/useBinanceTradeSse.ts`
- `EventSource('/api/binance/trades/sse')` + 초기 `GET /api/binance/trades/recent?limit=100`. `seenIds`로 중복 방지, 데스크톱은 `DESKTOP_MAX=100`건 유지(모바일은 무한).
- `scanState`: `watching|expanding|reconnecting`. 새 체결 시 `expanding`(애니 `ANIMATION_MS=500`), 애니 중 도착분은 즉시 삽입. 오류 시 `RECONNECT_DELAY_MS=1000` 후 재연결+재로드. `visibilitychange`/`pageshow(persisted)` 시 재연결. 모바일 무한스크롤 `loadMore(beforeId, limit=20)` → `/recent?before=&limit=`.

### 틱 SSE — `model/hook/useRawTickSse.ts`
- `EventSource('/api/binance/trades/tick-sse')`. 큐(`QUEUE_MAX=1000`)에 쌓고 `BATCH_MS=100`마다 `setTicks`로 배치 반영, 표시 `TICKS_MAX=100`건. 오류 시 `RECONNECT_DELAY_MS=1000` 후 재연결(초기화). 탭이 `OUT_DELAY_MS=180000`(3분) 이상 숨겨졌다 복귀하면 `reconnectNow`로 큐·목록 초기화 후 재연결.

### 시그널 SSE — `model/hook/useSignalSse.ts`
- `EventSource('/api/signal/stream/sse?symbol=${symbol}')`. 이벤트별 분리 수신: `aggtrade`→`aggTrades`(최근 100), `forceOrder`→`forceOrders`(최근 50), `oi`→`latestOi`(최신 1건). 오류 시 1초 재연결. 심볼 변경 시 `SYMBOL_CHANGE_DEBOUNCE_MS=300` 디바운스 후 재연결(불필요 재연결 방지).

### 표시 모델 — `model/display/binanceTickerDisplayModel.js`
- `buildBinanceTickerDisplayModel({ticker, upbitTicker, usdtKrwTicker})`: 현재가/고저가/변동률 파싱, `calcKrw = currentPrice × usdtKrwTicker.trade_price`(환율 있을 때), `premium = upbitTradePrice - calcKrw`, `premiumRate`. 색/부호(상승 `#2ecc71`, 하락 `#e74c3c`).
- 포맷터: `formatBinancePrice`($), `formatKrwPrice`(₩), `formatUsdDiff`, `formatBinanceVolume`(BTC), `formatPremiumKrwAbs`.

### 상태/안정화 보조
- `model/status/binanceLiveStatus.js` — `buildBinanceLiveStatus`: 연결 상태→배지(`connected` LIVE/초록, `connecting` 연결 중/주황, `disconnected` 연결 끊김/빨강), 연결+티커 있을 때 깜빡임(reduced-motion 존중).
- `model/panel/tickerPanelStability.js` + `model/hook/useTickerPanelStability.js` — `getTickerPanelMinimumSize`: 심볼 변경 등으로 `ticker===null`일 때 직전 패널 크기를 min-height/width로 고정해 레이아웃 시프트 방지.

### 지갑 — `model/hook/useBinanceWallet.js` + `model/wallet/*`
- 실시간성이 낮아 마운트 시 `GET /api/binance/account` 1회. `classifyWalletResponse`/`classifyWalletError`(`binanceWalletLoadPolicy.js`) → `applyWalletOutcome`(`binanceWalletState.js`)로 상태 전이. 상태 필드: `accountInfo, walletLoading, walletError, serverError`. UI `ui/wallet/BinanceWallet.tsx`는 `free`/`locked`가 0보다 큰 잔고만 표시.

### UI — `ui/ticker/`
- 데스크톱 `BinanceTicker.tsx`(3열 그리드), 모바일 `BinanceTickerMobile.tsx`(세로 스택, 768px 이하). 로딩 시 `shared/TickerSkeletonBox.tsx`(+`tickerSkeleton.js` shimmer 키프레임) 스켈레톤으로 레이아웃 시프트 방지.

## 연관 도메인
- 백엔드: `be-binance`(WS/SSE 엔드포인트·시그널), `be-upbit`(업비트 중계 `/ws/upbit-price`). 상위 화면: `fe-page-binance`(시세 비교), `fe-page-signal`, `fe-page-trade`. 상세 관계는 `index.md`.
