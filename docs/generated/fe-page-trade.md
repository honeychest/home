# 프론트 페이지: trade

> wiki-refresh로 실제 소스를 읽고 검증함(2026-06-24). 페이지 간 관계는 `index.md` 참고.

## 역할 요약
- **한 줄 정의** — 실시간 BTC 체결(틱) 데이터를 테이블/카드로 보고 큰 체결을 강조해주는 체결 모니터 페이지.
- **누가·언제 쓰나** — 실시간 체결 흐름과 대형 체결을 지켜보거나, 과거 체결을 조회·필터링하고 싶을 때.
- **핵심 기능** — ① SSE 실시간 틱 테이블(PC)·카드(모바일) ② 큰 거래 하이라이트 ③ 조회 사이드 패널 필터링(임계값·권한 정책).

## 이런 걸 물을 때 찾으면 된다 (검색 키워드)
- "체결 페이지 / 실시간 틱 테이블 / 트레이드 모니터"
- "큰 거래 강조 / 대형 체결 임계값 threshold"
- "체결 조회 필터 / SPOT FUTURES / 날짜 정렬 페이지네이션"
- "매수매도 누적 거래량 / 틱 누적"
- "체결 데이터 SSE /api/binance/trades"

## 핵심 개념·용어
- **틱(tick)**: 개별 체결. `useRawTickSse`(→`fe-domain-binance`)로 수신.
- **임계값(threshold)**: 대형 체결 강조 기준 금액. 서버 조회 후 관리자만 편집.
- **스캔 슬롯(ScanSlot)**: 임계값 이상 체결을 감지하는 애니메이션 영역.
- **하이라이트**: 새 체결의 최상단 행을 잠깐 강조(`HIGHLIGHT_DURATION_MS=500`).
- **누적 거래량**: 들어온 틱을 매수/매도로 분류해 합산한 총량(`reduceTickState`).

## 구조 / 흐름 (확인된 코드 기준)

파일 위치: `frontend/src/page/trade/`

### 진입·레이아웃 — `TradePage.jsx`
- 데스크톱 2열: 좌측 `TradeDesktopTradesTable`(체결 테이블)+상단 `TradeScanSlot`(스캔/임계값), 우측 `TickTable`(실시간 틱). 모바일은 `TradeMobileList`(카드)+`useTradeMobileLoadMore`(무한 스크롤). 조회 사이드패널 `TradePanel`.

### 실시간 수신·틱 누적 — `model/hook/useTickTotals.js`, `model/tickAccumulation.js`
- `useRawTickSse`의 `ticks`+`isReconnecting`을 `useTickTotals`가 처리. `reduceTickState`: 재연결이면 `initialTickState`로 초기화, 신규 틱만 식별해 `isBuyerMaker`면 매도/아니면 매수로 분류, 유효하지 않은 수량(NaN·비유한·0이하) 제외, 델타를 `totals`에 합산. 표시 `formatTickQtyTotal`(소수4자리).

### 하이라이트 — `model/hook/useNewTradeHighlight.js`, `model/newTradeHighlight.js`
- `detectNewFirstId`로 직전 첫 ID와 현재 `firstId` 비교, 바뀌면 신규로 감지. `HIGHLIGHT_DURATION_MS=500` 경과 후 자동 제거. PC `TradeDesktopTradesTable`은 신규행에 Skeleton, 모바일 `TradeMobileList`는 `styles.newRow`.

### 조회·필터 — `TradePanel.tsx`, `model/hook/useTradePanelSearch.ts`
- `useTradePanelSearch`로 심볼·시장(SPOT/FUTURES)·날짜·정렬·페이지크기 관리. `handleSearch`→`apiClient.get('/api/binance/trades?...')`, 결과는 `PageResult`. 행마다 시장/가격/금액(USD)/경과시간, `isBuyerMaker`로 매수/매도. `startItem`/`endItem`, `fetchPage`(이전/다음), `handleSizeChange`(page 0 리셋).

### 임계값·권한 — `TradePanel.tsx`, `model/hook/useTradeThreshold.js`, `model/thresholdPolicy.js`
- `apiClient.get('/api/binance/trades/threshold')` → `mapThresholdResponse`. 서버 `canEdit` + 클라 `hasAdminAccess`를 `composeCanEdit`로 결합해 편집 가능 결정. 적용 시 `apiClient.post('/api/binance/trades/threshold?value={value}')` → 성공 시 `onThresholdChange`. 입력은 `1` 초과 `10,000,000` 이하 정수만(`e/E/+/-/.` 차단). 표시 `formatThreshold`.

### 표시 유틸 — `model/tradeDisplayModel.js`
- `formatPrice`(소수2, 무효 `—`), `formatQty`(소수4), `formatValue`($, 1M↑ 'M'·1K↑ 'K'), `formatKrw`(USD×`USD_KRW_RATE=1450`, 콤마), `formatTickQtyTotal`(소수4, 기본 `0.0000`), `formatTime`(KST 24h 시:분:초), `getElapsed`(방금/n분 전/…), `commaInt`.

## 연관 도메인
- 훅: `fe-domain-binance`(`useRawTickSse`/`useBinanceTradeSse`). 백엔드: `be-binance`(`/api/binance/trades*`). 상세 관계는 `index.md`.
