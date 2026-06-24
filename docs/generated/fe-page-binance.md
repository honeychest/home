# 프론트 페이지: binance

> wiki-refresh로 실제 소스를 읽고 검증함(2026-06-24). 페이지 간 관계는 `index.md` 참고.

## 역할 요약
- **한 줄 정의** — Binance와 Upbit의 실시간 시세를 티커 카드로 나란히 비교해 보여주는 시세 페이지.
- **누가·언제 쓰나** — 선택한 코인의 양 거래소 시세와 USDT 환율(김치 프리미엄)을 한눈에 비교하고 싶을 때.
- **핵심 기능** — ① WebSocket 실시간 시세 수신(바이낸스·업비트) ② 코인/마켓 선택 탭 ③ PC·모바일 최적화 티커 카드 + 지갑 정보.

## 이런 걸 물을 때 찾으면 된다 (검색 키워드)
- "바이낸스 시세 페이지 / 업비트 비교 / 김치 프리미엄"
- "코인 선택 탭 BTC ETH SOL XRP"
- "USDT 환율 표시 / 1 USDT = ₩"
- "바이낸스 지갑 잔고 표시 / account"
- "PC 모바일 티커 카드 / 라이브 상태 점멸"

## 핵심 개념·용어
- **티커 카드(BinanceTickerCard)**: 코인 탭 + 라이브 상태 + USDT 환율 헤더 + PC/모바일 티커를 담는 핵심 카드.
- **liveStatus**: 연결상태+티커로 만든 상태 표시(점 색·배경·점멸). `buildBinanceLiveStatus`(→`fe-domain-binance`).
- **upbitCodes**: 선택 코인의 업비트 코드 + `KRW-USDT`(환율). `useUpbitWebSocket`에 전달.
- **패널 안정화**: 심볼 변경으로 `ticker`가 null일 때 직전 크기를 유지(`useTickerPanelStability`)해 레이아웃 시프트 방지.

## 구조 / 흐름 (확인된 코드 기준)

파일 위치: `frontend/src/page/binance/`

### 진입·레이아웃 — `BinancePage.jsx`
- `Layout` 안에 `BinancePageHeader`(거래소 페어 레이블, `getBinanceExchangePairLabel`) + `BinanceTickerCard` + `BinanceWalletCard`를 수직 배치.
- 반응형: `window.innerWidth < 768`로 `isMobile` 판단(resize 리스너). 패딩 16/32px, 컨테이너 maxWidth 모바일 100% / 그 외 1120px. 테마는 `usePageTheme`로 `theme-` 클래스 적용.

### 실시간 시세 — `BinancePage.jsx`
- `useBinanceWebSocket(selectedSymbol)` → `{ticker, status}`(→`fe-domain-binance`, WS `/ws/binance-price?symbol=`).
- `useUpbitWebSocket(upbitCodes)` → 업비트 티커(`/ws/upbit-price?codes=`).
- `buildBinanceLiveStatus({status, ticker})` → `liveStatus`. `useTickerPanelStability(ticker)` → `tickerWrapperRef`/`minimumSizeStyle`.
- 최종 `BinanceTicker`(PC)/`BinanceTickerMobile`(모바일)에 `ticker`/`upbitTicker`/`usdtKrwTicker` 전달.

### 코인/마켓 선택 — `ui/BinanceTickerCard.jsx`, `model/binanceTickerCardStyles.js`
- `BINANCE_MARKETS`(BTC/ETH/SOL/XRP, →`fe-domain-binance`) 기반 탭. 클릭 시 `onSelectSymbol`→`selectedSymbol` 갱신. `getSelectedBinanceMarket`로 `selectedCoin`, `useMemo`로 `upbitCodes` 계산. 활성 탭 스타일 `getCoinTabTone`.
- USDT 환율 라벨 `formatUsdtRateLabel`/`binanceTickerCardView.js`: 데이터 있으면 `1 USDT = ₩[금액]`.

### 지갑 — `BinancePage.jsx`, `ui/BinanceWalletCard.jsx`
- `useBinanceWallet()` → `{accountInfo, walletLoading, walletError, serverError}`(→`fe-domain-binance`, `GET /api/binance/account`). `serverError`면 `ErrorPage`, `walletLoading`이면 미렌더. `BinanceWalletCard`→내부 `BinanceWallet`(유의미 잔고만 표시).

## 연관 도메인
- 훅·표시 로직은 `fe-domain-binance`(시세/지갑/프리미엄), `fe-domain-weather`와 무관. 백엔드: `be-binance`, `be-upbit`. 상세 관계는 `index.md`.
