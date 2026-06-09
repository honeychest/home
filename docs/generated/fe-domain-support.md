# 프론트 도메인: support

> 이 문서는 로컬 LLM(gemma-4-26b-a4b-it-mlx)이 소스 코드를 근거로 자동 생성했다. 검증 전 초안이다.

## 목차
- 개요
- 기기 식별자 및 보안 토큰 관리
- 문의 데이터 전송 파이프라인
- 이미지 압축 및 처리 로직
- 보안 검사 시뮬레이션 및 상태 관리
- 문의 히스토리 조회 및 뷰 전환
- 테마 설정 및 관리자 권한 연동

## 개요

사용자의 문의를 관리자에게 전달하고, 기존 문의 내역(답변 포함)을 확인하는 기능을 제공합니다. `guestToken`을 기반으로 기기를 식별하며, 문의 전송 시 텍스트와 선택적 이미지 파일을 포함할 수 있습니다.

주요 기능 및 흐름은 다음과 같습니다:
- **문의 전송**: 사용자가 입력한 메시지와 첨부된 이미지를 `sendTelegramInquiry` 함수를 통해 서버로 전송합니다. 이미지 파일은 `compressImage` 함수를 통해 지정된 용량(`TARGET_BYTES`) 이하로 압축 과정을 거칩니다. (`frontend/src/domain/support/api/contactApi.js`, `frontend/src/domain/support/ui/TelegramPopup.jsx`)
- **문의 내역 조회**: `guestToken`을 사용하여 사용자의 기존 문의 목록을 가져오며, 답변이 포함된 경우 히스토리 뷰를 통해 관리자의 답변 내용을 확인할 수 있습니다. (`frontend/src/domain/support/api/contactApi.js`, `frontend/src/domain/support/ui/TelegramPopup.jsx`)
- **보안 및 상태 관리**: 문의 전송 시 `ALL_STEPS`에 정의된 조건(XSS 처리, Safe Browsing, VirusTotal)에 따라 시각적인 보안 체크 단계를 표시합니다. (`frontend/src/domain/support/ui/TelegramPopup.jsx`)
- **기기 식별**: `getGuestToken`을 통해 생성된 UUID를 `localStorage`에 저장하여 기기별 고유 식별자로 활용합니다. (`frontend/src/domain/support/api/contactApi.js`)

## 기기 식별자 및 보안 토큰 관리

기기 식별을 위해 `localStorage`에 `chs_guest_token`이라는 키로 저장되는 UUID를 사용합니다. `frontend/src/domain/support/api/contactApi.js`의 `getGuestToken` 함수는 호출 시 `localStorage`에서 해당 토큰을 조회하며, 만약 존재하지 않을 경우 `generateUUID` 함수를 통해 생성하여 저장한 뒤 반환합니다. 이 토큰은 문의 전송(`sendTelegramInquiry`), 문의 목록 조회(`fetchInquiries`), 답변 읽음 처리(`markReplyRead`) 시 식별자로 전달됩니다.

## 문의 데이터 전송 파이프라인

사용자가 문의 내용을 입력하고 전송 버튼을 누르면 다음과 같은 파이프라인을 통해 데이터가 처리됩니다.

1.  **데이터 검증 및 준비**: `TelegramPopup.jsx`에서 사용자가 입력한 텍스트의 길이를 확인하여 `MAX_LENGTH`(300자) 초과 여부를 검증합니다. 이미지가 첨부된 경우 `compressImage` 함수를 통해 지정된 품질(`QUALITIES`)로 이미지를 압축하며, 압축된 결과물은 `Blob` 형태로 관리됩니다.
2.  **보안 체크 단계 설정**: `handleSubmit` 함수가 호출되면 `generateUUID`를 통해 새로운 `inquiryId`를 생성합니다. 이후 `ALL_STEPS`에 정의된 규칙(XSS 처리, Safe Browsing, VirusTotal) 중 입력된 텍스트와 파일 존재 여부에 부합하는 단계들을 `activeSteps`로 추출합니다.
3.  **API 요청 전송**: `sendTelegramInquiry` 함수가 호출되어 `FormData` 객체를 생성합니다. 이 객체에는 문의 메시지(`message`), 생성된 `inquiryId`, 기기 식별자인 `guestToken`, 그리고 선택적으로 압축된 이미지 파일(`file`)이 포함됩니다. 생성된 데이터는 `apiClient.post`를 통해 `/api/support/inquiry` 엔드포인트로 전송됩니다. (`frontend/src/domain/support/api/contactApi.js`, `frontend/src/domain/support/ui/TelegramPopup.jsx`)
4.  **상태 업데이트 및 시각적 피드백**: 전송이 시작되면 `status`가 `sending`으로 변경됩니다. 전송 중에는 `useEffect`에 의해 1초마다 `checkStep`이 증가하며, 설정된 보안 체크 단계가 순차적으로 완료되는 애니메이션 효과를 제공합니다.
5.  **완료 처리**: API 호출이 성공하면 `onSent` 콜백이 실행되고 `status`가 `success`로 변경됩니다. 모든 보안 체크 단계(`isDone`)가 완료되면 1.5초 후 팝업이 자동으로 닫힙니다. 만약 전송 중 오류(예: HTTP 429)가 발생하면 `status`는 다시 `idle`로 돌아가며 사용자에게 알림을 표시합니다. (`frontend/src/domain/support/api/contactApi.js`, `frontend/src/domain/support/ui/TelegramPopup.jsx`)

## 이미지 압축 및 처리 로직

이미지 파일이 선택되면 `frontend/src/domain/support/ui/TelegramPopup.jsx`의 `handleFileChange` 함수가 실행되며, 내부적으로 `compressImage` 함수를 호출하여 압축을 진행합니다.

압축 로직은 다음과 같은 단계로 동작합니다:
1. `compressImage` 함수는 `URL.createObjectURL`을 통해 생성된 객체 URL을 사용하여 이미지를 로드합니다.
2. `canvas` 엘리먼트를 생성하고 이미지의 원본 크기(`naturalWidth`, `naturalHeight`)를 캔버스 크기에 할당한 뒤, 이미지를 캔버스에 그립니다.
3. `QUALITIES` 배열(`[0.9, 0.7, 0.5, 0.3]`)에 정의된 품질 값을 순차적으로 적용하는 `tryQuality` 재귀 함수를 통해 압축을 시도합니다.
4. 각 단계에서 `canvas.toBlob`을 사용하여 `image/jpeg` 형식으로 변환하며, 생성된 Blob의 크기가 `TARGET_BYTES`(8MB) 이하가 될 때까지 품질을 낮추며 반복합니다.
5. 압축이 완료되면 최종적으로 생성된 Blob 객체가 반환됩니다.

압축 과정에서 오류가 발생하거나 이미지 로드에 실패할 경우 `reject`를 통해 에러를 전달하며, 성공 시에는 `frontend/src/domain/support/ui/TelegramPopup.jsx`의 `setFile`과 `setPreview`를 통해 압축된 파일과 미리보기용 URL이 각각 상태에 저장됩니다.

## 보안 검사 시뮬레이션 및 상태 관리

문의 전송 시 `status` 상태에 따라 보안 검사 단계가 진행됩니다. `ALL_STEPS` 배열에 정의된 'XSS 처리', 'Safe Browsing', 'VirusTotal' 등의 체크 항목은 `handleSubmit` 호출 시 입력된 텍스트와 파일 존재 여부에 따라 `check` 함수를 통해 동적으로 필터링되어 `activeSteps`에 저장됩니다 (`frontend/src/domain/support/ui/TelegramPopup.jsx`).

`status`가 `sending` 또는 `success`인 경우, `useEffect`를 통해 1초마다 `checkStep` 상태가 1씩 증가하며 시뮬레이션이 진행됩니다 (`frontend/src/domain/support/ui/TelegramPopup.jsx`). `checkStep` 값과 `activeSteps`의 인덱스를 비교하여 각 단계의 상태를 `done`, `active`, `pending`으로 구분하여 UI에 반영합니다 (`frontend/src/domain/support/ui/TelegramPopup.jsx`). 모든 단계가 완료되어 `isDone` 조건이 충족되면 1.5초 후 팝업이 자동으로 닫힙니다 (`frontend/src/domain/support/ui/TelegramPopup.jsx`).

## 문의 히스토리 조회 및 뷰 전환

`TelegramPopup` 컴포넌트는 `inquiries` props에 포함된 데이터 중 관리자의 답변(`replyText`)이 존재하는지 여부에 따라 뷰를 결정합니다.

*   **뷰 전환 로직**: `inquiries` 배열 내에 `replyText`를 가진 요소가 하나라도 존재할 경우(`hasAnyReply`), 팝업은 'history' 뷰로 설정됩니다. 반대로 답변이 없는 경우에는 'form' 뷰가 표시됩니다. (`frontend/src/domain/support/ui/TelegramPopup.jsx`)
*   **히스토리 뷰 구성**: 'history' 뷰에서는 `inquiries` 배열을 역순으로 정렬하여 표시하며, 각 항목은 사용자의 문의 내용과 관리자의 답변 내용을 포함합니다. (`frontend/src/domain/support/ui/TelegramPopup.jsx`)
*   **새 문의 전환**: 히스토리 뷰 하단의 '새 문의하기' 버튼을 클릭하면 `setView('form')`이 호출되어 다시 폼 작성 화면으로 전환됩니다. (`frontend/src/domain/support/ui/TelegramPopup.jsx`)
*   **스크롤 제어**: 히스토리 뷰로 전환되거나 `inquiries` 데이터가 갱신될 경우, `historyBodyRef`를 사용하여 스크롤 위치를 최하단으로 이동시킵니다. (`frontend/src/domain/support/ui/TelegramPopup.jsx`)

## 테마 설정 및 관리자 권한 연동

특정 페이지 경로에 따라 테마를 매핑하는 `DARK_PAGE_MAP` 객체를 통해 `/analysis`, `/binance`, `/trade`, `/signal` 경로에서 각각 `analysis`, `binance`, `trade`, `signal` 테마 키를 추출합니다 (`frontend/src/domain/support/ui/TelegramPopup.jsx`).

`useAdminAccess` 훅을 통해 관리자 권한(`hasAdminAccess`)이 확인된 경우에만 테마 셀렉터가 표시됩니다 (`frontend/s/domain/support/ui/TelegramPopup.jsx`). 사용자가 `ThemeSelect`를 통해 테마를 변경하면 `setPageTheme` 함수가 호출되어 해당 페이지의 테마가 설정됩니다 (`frontend/src/domain/support/ui/TelegramPopup.jsx`).
