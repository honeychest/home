# 프론트 교차영역: shared

> wiki-refresh로 실제 소스를 읽고 검증함(2026-06-24). 페이지 간 관계는 `index.md` 참고.

## 한 줄 요약
앱 전역 공용 모듈: 관리자 권한 인증(컨텍스트·훅), 공통 상수/유틸, 그리고 재사용 UI(레이아웃 Header/Footer/Layout, MiniChart 차트, 다이얼로그, shadcn 컴포넌트).

## 이런 걸 물을 때 찾으면 된다 (검색 키워드)
- "관리자 권한 확인은 어떻게 해? canAccess / hasAdminAccess"
- "헤더 메뉴 / 네비게이션 / 서버 이름 표시 X-Server-Name"
- "공통 레이아웃 / 문의 팝업 / 방문자 로그 / 답변 SSE"
- "Tailwind 클래스 병합 cn / 천단위 콤마 / 미디어쿼리 훅"
- "MiniChart 캔들/라인 차트 / 마커 / 툴팁"

## 핵심 개념·용어
- **AdminAuthContext / useAdminAuth**: 관리자 접근 가능 여부(`canAccess`)를 앱 전역에 공유하는 컨텍스트. 헤더의 관리자 전용 메뉴 노출 등에 쓴다.
- **useAdminAccess**: 위와 별개로, 쿠키 디버그 엔드포인트로 권한을 확인하고 *모듈 단위로 캐싱*하는 훅(테마 셀렉터 등 가벼운 곳에서 사용).
- **Layout/Header/Footer**: 모든 페이지를 감싸는 골격. 헤더는 메뉴+서버 인디케이터, 레이아웃은 문의 팝업·방문자 로그·답변 SSE를 관리.
- **MiniChart**: `lightweight-charts` 기반 공용 캔들/라인 차트.

## 구조 / 흐름 (확인된 코드 기준)

파일 위치: `frontend/src/shared/`

### 관리자 인증 — `auth/`, `lib/useAdminAccess.js`
- `auth/AdminAuthContext.jsx`의 `AdminAuthProvider`: 마운트 시 `GET /api/admin/data-gap/access` → `canAccess`(boolean). 403이면 `isForbidden=true`, 실패 시 `canAccess=false`. 컨텍스트 값 `{canAccess, isForbidden, refresh}`(`refresh`=`fetchAccess` 재조회). 컨텍스트 객체는 `auth/AdminAuthContext.js`.
- `auth/useAdminAuth.js`: 위 컨텍스트 소비 훅(Provider 밖에서 쓰면 에러).
- `lib/useAdminAccess.js`: `GET /api/admin/test/auth/debug/cookie-info` 성공 여부로 권한 판정. 모듈 변수 `cachedAdminAccess`/`pendingAdminAccessCheck`로 중복요청 방지·결과 캐싱. `useAdminAccess(enabled=true)` → `{hasAdminAccess, isCheckingAdminAccess}`.

### 상수 — `constant/`
- `routes.ts`: 일부 경로 상수(`trade/binance/signal/logistics/test/errorTest`). (실제 헤더 메뉴는 아래 `Header.jsx`의 `NAV_ITEMS`가 별도 관리.)
- `colors.ts`: 공용 색상 상수.

### 유틸 — `lib/`
- `lib/utils.ts`: `cn(...inputs)`(clsx + tailwind-merge로 Tailwind 클래스 병합), `formatWithComma(v)`(천단위 콤마, 숫자 아니거나 무한이면 빈 문자열).
- `lib/generateUUID.js`: UUID 생성(보안 컨텍스트 아니면 폴백).
- `lib/useMediaQuery.ts`: `useMediaQuery(query)` — `matchMedia` 기반 boolean 반응 훅(SSR 가드 포함).

### 레이아웃·네비 — `ui/layout/`
- `Header.jsx`: `NAV_ITEMS`(Binance·Trade·Signal·Analysis·Logistics·Winner·Monitor·Admin, 그리고 `requireAdmin: true`인 Test(`/admin/test`)·Editor(`/winner/editor`)). 관리자 항목은 `useAdminAuth().canAccess === true`일 때만 노출(`visibleNavItems`). 마운트 시 `GET /api/binance/price` 응답 헤더 `x-server-name`을 읽어 서버 인디케이터 표시. 모바일 가로 메뉴 스크롤 위치를 `sessionStorage['header.nav.scrollLeft']`에 저장/복원, `/signal` 호버 시 `preloadSignalPage`.
- `Layout.jsx`: `getGuestToken` 기반. `loadInquiries`(=`fetchInquiries`)로 문의 목록·미읽음(`replyText && !readAt`→`hasReply`) 관리. 마운트 1회 `POST /api/visitor/log {path}`. `EventSource('/api/support/reply/sse?guestToken=...')`의 `reply` 이벤트마다 목록 갱신. `handleOpen` 시 미읽음 답변 `markReplyRead` 일괄 처리. `<TelegramPopup>`(문의), `<OverLoadToast>`, `<Footer onAdminClick hasReply>` 배치. props `children/footerCenter/enableSupport`.
- `Footer.jsx`: `COMMON_TECH=['AWS','Linux','Spring Boot','Nginx','React','MySQL']` + 페이지별 `centerTech`. `onAdminClick`(문의 열기)·`hasReply`(빨간 점 배지).

### UI 위젯 — `ui/`
- `ui/chart/MiniChart.jsx`: `lightweight-charts`(`createChart`, `CandlestickSeries`/`LineSeries`, `createSeriesMarkers`). `chartType` `candle|line`. `highlights`(세로 띠), `buildMarkers`(화살표 마커), `subscribeCrosshairMove`(툴팁: 종가·거래량·델타·고저 대비), 최고/최저 `priceLine`, `liveCandle`로 실시간 업데이트. 스크롤/리사이즈는 `DEBOUNCE_MS=200` 디바운스. `scaleMargins`로 Y축 여백 최소화.
- `ui/dialog/ConfirmDialog.jsx`: `createPortal`로 body에 모달. Escape·오버레이 클릭 시 `onCancel`. props `open/title/description/confirmText/cancelText/onConfirm/onCancel`.
- `ui/DesktopViewGate.jsx`: `DesktopViewGate`(데스크톱 뷰 강제 게이트) + `DesktopViewResetButton`.
- `ui/samples/visualSamples.js`: 차트/시각 샘플 데이터.
- `ui/shadcn/`: shadcn/ui 기반 공용 컴포넌트(`badge, button, input, input-otp, select, sheet, skeleton, table`). radix-ui + `class-variance-authority`(cva) 패턴.

## 연관 도메인
- 문의 팝업은 `fe-domain-support`, 토스트·챗봇은 `fe-components`, API 클라이언트는 `fe-api`. 관리자 권한은 백엔드 `/api/admin/*`. 상세 관계는 `index.md`.
