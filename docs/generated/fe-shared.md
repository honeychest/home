# 프론트 교차영역: shared

> 이 문서는 로컬 LLM(gemma-4-26b-a4b-it-mlx)이 소스 코드를 근거로 자동 생성했다. 검증 전 초안이다.

## 목차
- 개요
- 관리자 권한 인증 및 상태 관리
- 공용 유틸리티 및 데이터 포맷팅
- UI 컴포넌트: 차트 및 시각화 엔진
- UI 컴포넌트: 레이아웃 및 네비게이션 시스템
- UI 컴포넌트: 인터랙티브 다이얼로그 및 폼 요소
- 데이터 흐름 및 API 연동 구조

## 개요

이 도메인은 애플리케이션 전역에서 사용되는 공용 상수, 유틸리티 함수, UI 컴포넌트 및 인증 관련 로직을 포함합니다.

**1. 인증 및 권한 관리**
관리자 접근 권한을 관리하기 위해 `AdminAuthContext.js`와 `AdminAuthContext.jsx`를 통해 `/api/admin/data-gap/access` 엔드포인트를 호출하여 권한 상태(`canAccess`, `isForbidden`)를 앱 전역에 공유합니다. 이를 소비하기 위한 커스텀 훅 `useAdminAuth.js`가 제공됩니다. 또한, 별도의 로직으로 `/api/admin/test/auth/debug/cookie-info`를 호출하여 관리자 접근 여부를 확인하고 캐싱하는 `useAdminAccess.js`가 존재합니다.

**2. 공용 상수 및 유틸리티**
앱 전역에서 사용하는 색상 값(`colors`)이 `colors.ts`에 정의되어 있으며, 라우트 경로 상수(`routes`)가 `routes.ts`에 관리됩니다. 유틸리티 측면에서는 UUID 생성 기능(`generateUUID.js`), Tailwind 클래스 병합을 위한 `cn` 함수 및 숫자 포맷팅을 위한 `formatWithComma` 함수(`utils.ts`), 그리고 미디어 쿼리 대응을 위한 `useMediaQuery.ts`를 제공합니다.

**3. UI 컴포넌트**
다양한 목적의 재사용 가능한 컴포넌트들이 포함되어 있습니다.
*   **차트 및 시각화**: `MiniChart.jsx`는 `lightweight-charts`를 기반으로 캔들스틱 및 라인 차트를 렌더링하며, 하이라이트 및 마커 기능을 포함합니다. 시각적 효과 샘플은 `visualSamples.js`에 정의되어 있습니다.
*   **레이아웃 및 구조**: 상단 헤더(`Header.jsx`), 하단 푸터(`Footer.jsx`), 그리고 페이지 전체를 감싸는 공통 레이아웃(`Layout.jsx`)이 구성되어 있습니다.
*   **대화상자 및 인터페이스**: 확인용 `ConfirmDialog.jsx`, 데스크톱 뷰 제어를 위한 `DesktopViewGate.jsx` 및 `DesktopViewResetButton`, 그리고 Shadcn UI 기반의 다양한 컴포넌트(Badge, Button, Input, Select, Sheet, Skeleton, Table 등)가 포함되어 있습니다.

## 관리자 권한 인증 및 상태 관리

관리자 접근 권한은 `AdminAuthContext.js`에서 생성된 컨텍스트를 통해 앱 전역에서 공유됩니다. `AdminAuthProvider`는 `/api/admin/data-gap/access` 엔드포인트를 호출하여 관리자 접근 가능 여부(`canAccess`)와 403 응답 시 발생하는 권한 거부 상태(`isForbidden`)를 관리합니다. `fetchAccess` 메서드를 통해 권한 상태를 수동으로 갱신할 수 있습니다.

`useAdminAuth.js`는 `AdminAuthContext`를 소비하기 위한 커스텀 훅으로, 해당 컨텍스트가 `AdminAuthProvider` 내부에서 사용되지 않을 경우 에러를 발생시킵니다.

별도의 로직인 `useAdminAccess.js`는 `/api/admin/test/auth/debug/cookie-info` 호출을 통해 관리자 접근 권한을 확인합니다. 이 과정에서 `cachedAdminAccess`와 `pendingAdminAccessCheck` 변수를 사용하여 요청 중복을 방지하고 결과를 캐싱합니다. `useAdminAccess` 훅은 설정된 `enabled` 값에 따라 권한 확인 여부를 결정하며, 현재의 권한 상태(`hasAdminAccess`)와 확인 중 여부(`isCheckingAdminAccess`)를 반환합니다.

`Header.jsx`에서는 `useAdminAuth` 훅을 사용하여 `canAccess` 상태를 가져오며, 이를 바탕으로 관리자 전용 메뉴(`requireAdmin: true`)의 표시 여부를 결정합니다.

## 공용 유틸리티 및 데이터 포맷팅

`frontend/src/shared/lib/utils.ts`에 정의된 `cn()` 함수는 `clsx`와 `tailwind-merge`를 사용하여 Tailwind 클래스를 병합하는 기능을 제공합니다. 또한, 동일 파일에 정의된 `formatWithComma()` 함수는 숫자를 입력받아 천 단위 콤마를 포함한 문자열로 포맷팅하며, 입력값이 숫자가 아니거나 유한하지 않은 경우 빈 문자열을 반환합니다.

## UI 컴포넌트: 차트 및 시각화 엔진

`MiniChart` 컴포넌트는 `lightweight-charts` 라이브러리를 기반으로 하며, 캔들스틱(`candle`) 및 라인(`line`) 차트 타입을 지원합니다. `highlights` 배열을 통해 특정 인덱스에 색상이 적용된 세로 띠를 생성하거나, `buildMarkers` 함수를 통해 차트 하단에 화살표 형태의 마커를 표시할 수 있습니다. `calcByCoordinate` 함수는 차트의 타임스케일 좌표를 계산하여 하이라이트 영역을 시각화합니다.

차트의 Y축 여백은 `scaleMargins` 설정을 통해 상하 2%로 최소화되어 있으며, 데이터의 최고점과 최저점을 기반으로 점선 형태의 `priceLine`이 자동 생성됩니다. 툴팁 기능은 마우스 커서 이동 시(`subscribeCrosshairMove`) 활성화되며, 해당 시점의 종가, 거래량, 델타(순매수/순매도), 그리고 당일 고점/저점 대비 차이 정보를 제공합니다.

`liveCandle` 프롭을 통해 실시간 데이터 업데이트를 지원하며, `DEBOUNCE_MS`(200ms)를 활용한 디바운스 처리를 통해 차트 스크롤 및 리사이즈 시 하이라이트 위치를 최적화하여 갱신합니다.

- `frontend/src/shared/ui/chart/MiniChart.jsx`

## UI 컴포넌트: 레이아웃 및 네비게이션 시스템

앱의 전체적인 구조를 담당하는 레이아웃과 네비게이션 시스템은 다음과 같이 구성되어 있습니다.

### 레이아웃 및 네비게이션 구조
앱의 기본 골격은 `Layout.jsx`를 통해 제공되며, 상단에는 `Header.jsx`, 하단에는 `Footer.jsx`가 배치됩니다.

*   **상단 헤더 (Header)**
    `Header.jsx`는 앱의 주요 메뉴 네비게이션과 서버 상태 인디케이터를 포함합니다.
    *   **네비게이션 메뉴**: `NAV_ITEMS` 상수에 정의된 경로들을 기반으로 메뉴가 생성됩니다. 특히 관리자 권한이 필요한 항목(`requireAdmin: true`)은 `useAdminAuth.js`에서 제공하는 `canAccess` 상태에 따라 동적으로 노출 여부가 결정됩니다.
    *   **서버 인디케이터**: 마운트 시 `/api/binance/price`를 호출하여 응답 헤더의 `X-Server-Name` 값을 읽어와 서버 환경을 표시합니다.
    *   **스크롤 제어**: 모바일 환경에서의 사용자 경험을 위해 네비게이션 스크롤 위치를 `sessionStorage`에 저장하고 복구하는 로직이 포함되어 있습니다.

*   **하단 푸터 (Footer)**
    `Footer.jsx`는 기술 스택 정보와 브랜드 정보를 제공합니다.
    *   **기술 스택 표시**: 공통 기술(`COMMON_TECH`)과 페이지별로 전달받은 `centerTech` 목록을 각각 좌측과 중앙에 표시합니다.
    *   **관리자 문의 기능**: `Layout.jsx`로부터 전달받은 `onAdminClick` 콜백과 답변 여부(`hasReply`)에 따라 브랜드 영역의 동작이 결정됩니다.

*   **메인 콘텐츠 영역 (Main)**
    `Layout.jsx`의 `<main>` 태그 내부에 페이지별 콘텐츠가 `children`으로 렌더링됩니다. 또한, 앱 전역에서 사용되는 `OverLoadToast.jsx`가 레이아웃 최상단에 배치되어 알림을 처리합니다.

*   **지원 및 팝업 시스템 (Support)**
    `Layout.jsx`는 `TelegramPopup.jsx`를 통해 문의 시스템을 관리합니다.
    *   **실시간 응답**: `EventSource`를 이용한 SSE(Server-Sent Events) 구독을 통해 서버로부터 새로운 답변이 도착하면 `loadInquiries`를 호출하여 목록을 갱신합니다.
    *   **미읽음 관리**: 답변이 도착했을 경우 `hasReply` 상태가 활성화되며, 사용자가 팝업을 열면 미읽음 답변들을 읽음 처리(`markReplyRead`)하는 로직이 수행됩니다.

## UI 컴포넌트: 인터랙티브 다이얼로그 및 폼 요소

`ConfirmDialog`는 `react-dom`의 `createPortal`을 사용하여 `document.body`에 렌더링되는 모달 창입니다. `Escape` 키 입력 시 `onCancel` 콜백을 호출하는 이벤트 리스너가 포함되어 있으며, 배경(overlay) 클릭 시에도 `onCancel`이 실행됩니다. (frontend/src/shared/ui/dialog/ConfirmDialog.jsx)

`Sheet`는 `radix-ui`의 `Dialog`를 기반하며, 설정된 방향(`top`, `right`, `bottom`, `left`)에 따라 화면 가장자리에 나타나는 사이드 패널 형태의 UI를 제공합니다. (frontend/src/shared/ui/shadcn/sheet.tsx)

`InputOTP`는 `input-otp` 라이브러리를 사용하여 일회용 비밀번호(OTP) 입력을 위한 그룹화된 입력 필드를 제공합니다. `InputOTPGroup`과 `InputOTPSlot`을 통해 각 자리수별 입력 칸을 구성할 수 있습니다. (frontend/src/shared/ui/shadcn/input-otp.tsx)

`Select`는 `radix-ui`의 `Select`를 기반하며, `SelectTrigger`, `SelectValue`, `SelectContent`, `SelectItem` 등의 하위 컴포넌트로 구성되어 드롭다운 선택 기능을 제공합니다. (frontend/src/shared/ui/shadcn/select.tsx)

`Input`은 기본적인 텍ext 입력 필드를 제공하며, `type` 속성을 통해 다양한 입력 타입을 지원합니다. (frontend/src/shared/ui/shadcn/input.tsx)

`Button`은 `class-variance-authority`(cva)를 사용하여 다양한 시각적 스타일(`variant`)과 크기(`size`)를 제공하는 버튼 컴포넌트입니다. (frontend/src/shared/ui/shadcn/button.tsx)

`Badge`는 `class-variance-authority`(cva)를 사용하여 상태나 정보를 나타내는 작은 라벨 형태의 UI를 제공합니다. (frontend/src/shared/ui/shadcn/badge.tsx)

`Skeleton`은 `animate-pulse` 애니메이션을 사용하여 데이터 로딩 중임을 나타내는 플레이스홀더 UI를 제공합니다. (frontend/src/shared/ui/shadcn/skeleton.tsx)

## 데이터 흐름 및 API 연동 구조

`AdminAuthContext.jsx`의 `AdminAuthProvider`는 컴포넌트 마운트 시 및 `refresh` 호출 시 `/api/admin/data-gap/access` 엔드포인트를 통해 관리자 접근 권한을 확인합니다. 응답 데이터의 `canAccess` 값에 따라 상태를 설정하며, 403 에러 발생 시 `isForbidden` 상태를 `true`로 변경합니다. 이 정보는 `AdminAuthContext.js`를 통해 생성된 컨텍스트를 통해 앱 전역으로 공유됩니다.

`useAdminAuth.js`는 `AdminAuthContext.js`를 사용하여 컨텍스트에 접근하며, 이를 통해 관리자 권한 상태(`canAccess`)와 금지 여부(`isForbidden`)를 소비합니다.

`useAdminAccess.js`는 별도의 캐싱 메커니즘을 사용하여 관리자 접근 권한을 확인합니다. `checkAdminAccess` 함수는 `/api/admin/test/auth/debug/cookie-info`를 호출하여 권한을 검증하며, 한 번 확인된 결과는 `cachedAdminAccess` 변수에 저장되어 재사용됩니다. `useAdminAccess` 훅은 설정된 `enabled` 인자에 따라 이 검증 로직을 실행하며, 결과값(`hasAdminAccess`)과 확인 중 여부(`isCheckingAdminAccess`)를 반환합니다.

`Header.jsx`는 마운트 시 `/api/binance/price`를 호출하여 응답 헤더의 `X-Server-Name` 값을 읽어 서버 인디케이터를 표시합니다.

`Layout.jsx`는 `guestToken`을 기반으로 문의 목록을 관리합니다. `loadInquiries` 함수는 `fetchInquiries(guestToken)`를 호출하여 데이터를 가져오며, `markReplyRead` 함수를 통해 미읽음 답변을 읽음 처리합니다. 또한, `EventSource`를 사용하여 `/api/support/reply/sse?guestToken=${guestToken}` 엔드포인트를 통해 SSE(Server-Sent Events)를 구독함으로써, 서버로부터 `reply` 이벤트가 발생할 때마다 실시간으로 문의 목록을 갱신합니다.
