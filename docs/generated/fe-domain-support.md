# 프론트 도메인: support

> wiki-refresh로 실제 소스를 읽고 검증함(2026-06-24). 페이지 간 관계는 `index.md` 참고.

## 한 줄 요약
사용자가 관리자에게 문의(텍스트+선택 이미지)를 보내고, 기존 문의·관리자 답변을 확인하는 텔레그램 연동 문의 팝업 도메인. `guestToken`(localStorage UUID)으로 기기를 식별한다.

## 이런 걸 물을 때 찾으면 된다 (검색 키워드)
- "문의하기 / 관리자 문의 / 텔레그램 문의는 어떻게 동작해?"
- "guestToken / 기기 식별 / 로그인 없이 문의 추적"
- "문의 이미지 첨부 / 압축 / 용량 제한"
- "보안 검사 단계 XSS Safe Browsing VirusTotal"
- "문의 내역 / 관리자 답변 보기 / 테마 변경"

## 핵심 개념·용어
- **guestToken**: 로그인 없이 기기를 구분하는 영구 UUID. `localStorage` 키 `chs_guest_token`에 저장(없으면 생성). 모든 문의 API에 식별자로 전달.
- **inquiryId**: 문의 1건마다 프론트에서 만드는 UUID. `crypto.randomUUID()` 우선, HTTP(비보안 컨텍스트) 환경 폴백 포함.
- **보안 검사 시뮬레이션**: 실제 검사가 아니라 전송 중 보여주는 단계 애니메이션(신뢰감 UX). `ALL_STEPS` 중 조건 맞는 것만 노출.
- **테마 셀렉터**: 다크 테마 페이지(`/analysis`,`/binance`,`/trade`,`/signal`)에서 관리자만 보이는 페이지별 테마 변경 UI.

## 구조 / 흐름 (확인된 코드 기준)

파일 위치: `frontend/src/domain/support/` (2개 파일: `api/contactApi.js`, `ui/TelegramPopup.jsx`)

### API — `api/contactApi.js` (`apiClient` 기반, base `/api/support`)
- `getGuestToken()`: `localStorage['chs_guest_token']` 조회, 없으면 `generateUUID()`로 생성·저장.
- `sendTelegramInquiry(message, file, inquiryId, guestToken)`: `FormData`(message, inquiryId, guestToken, 선택 `file`(이미지))를 `POST /api/support/inquiry`.
- `fetchInquiries(guestToken)`: `GET /api/support/inquiries?guestToken=...` → `[{ inquiryId, message, createdAt, replyText, repliedAt, readAt }]`(최신순).
- `markReplyRead(inquiryId, guestToken)`: `PATCH /api/support/reply/{inquiryId}/read`.

### 팝업 UI — `ui/TelegramPopup.jsx`
- 상수: `MAX_LENGTH=300`(문의 글자수), `TARGET_BYTES=8*1024*1024`(8MB, 이미지 압축 목표), `QUALITIES=[0.9,0.7,0.5,0.3]`.
- **이미지 압축**(`compressImage`): canvas로 `image/jpeg` 변환, `QUALITIES`를 순서대로 낮춰가며 Blob 크기가 `TARGET_BYTES` 이하가 될 때까지 시도(마지막은 0.3 강제).
- **보안 체크 단계**(`ALL_STEPS`): `XSS 처리`(항상), `Safe Browsing`(텍스트에 URL 있을 때), `VirusTotal`(파일 첨부 시). `handleSubmit`에서 조건 맞는 단계만 `activeSteps`로 추림.
- **전송 흐름**: `status`가 `idle`→(파일 선택 시 `compressing`)→`sending`→`success`. `sending/success` 동안 `useEffect`가 1초마다 `checkStep`을 올려 단계 애니메이션 진행. 모든 단계 완료(`isDone`) 시 1.5초 후 자동 닫힘. 전송 실패 시 `status`를 `idle`로 되돌리고, HTTP 429면 "요청이 너무 많습니다" 알림.
- **뷰 전환**: 답변(`replyText`)이 하나라도 있으면(`hasAnyReply`) 팝업 열 때 `history` 뷰(과거 문의+답변, 오래된→최신), 없으면 `form` 뷰. '새 문의하기'로 폼 전환.
- **테마**: 현재 경로가 `DARK_PAGE_MAP`(`/analysis`,`/binance`,`/trade`,`/signal`)에 매칭되고 `useAdminAccess`로 관리자 권한이 있으면 `ThemeSelect`(다크/블랙/Teal/Harbor) 노출 → `setPageTheme(themeKey, value)`.

## 연관 도메인
- 백엔드 문의 처리(`/api/support/*`)와 텔레그램 알림. 팝업은 공통 레이아웃/에러 화면에서 호출(`fe-shared`). 상세 관계는 `index.md`.
