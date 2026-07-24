# HANDOFF — 일회성 작업 인수인계 (완료되면 이 파일을 비운다)

> 용도: recipe 처럼 상설 현황판(progress.html)이 없는 일회성 작업의 세션 간 인계.
> 새 세션(Claude·Codex 무관)은 이 파일이 비어 있지 않으면 먼저 읽고 이어서 작업한다.
> 완료 후에는 이 안내 블록만 남기고 내용을 지운다 (비대화 방지).

## recipe 네이티브 앱 전환 대비 점검 — 마지막 항목 남음

배경: `docs/recipe/CONTEXT.md`의 "2단계: Android 앱 전환" 대비, improve-codebase-architecture
스킬로 recipe 도메인(frontend+springboot) 전체를 훑어 마찰 후보 5개를 찾음. 2·4(프론트 부분)는
완료, 3·5는 조사 결과 "지금은 손대지 않는 게 맞음"으로 판정(아래 참고). 1번(세션 쿠키
SameSite)만 사용자가 새 세션에서 최종 작업하기로 결정 — 아직 미착수.

### 남은 작업 — 1) 세션 쿠키 SameSite=Lax
- Files: `springboot/.../domain/recipe/auth/GikkaAuthController.java`(`sessionCookie()`),
  `config/GikkaSecurityConfig.java`
- 문제: 쿠키가 SameSite=Lax로 발급되는데, 이건 "같은 오리진"이라는 전제 위의 설계(같은 이유로
  CSRF 토큰도 생략됨). 네이티브 웹뷰는 API 서버와 다른 오리진에서 호출하게 되므로, 로그인
  응답은 받아도 이후 요청에 쿠키가 안 실려 401 반복이 날 위험이 있음.
- 결정할 것: SameSite=None 전환(오리진이 다르면 Secure+SameSite=None 필요) 또는 쿠키 대신
  Authorization 헤더 토큰 방식으로 전환. 어느 쪽이든 CORS 허용 오리진(현재 미설정 — 아래
  참고)도 이 시점에 함께 정해야 함.
- 이미 준비된 것(후보 4에서 완료): 프론트 `data/http.ts`에 `apiUrl()` 오리진 접두사 seam이
  이미 있어 base URL만 채우면 프론트 쪽은 대부분 준비됨(`authRepository.ts` 포함 전 저장소가
  이 seam을 거침, `credentials:'include'`도 이미 적용됨). 백엔드 CORS Bean은 아직 없음 —
  네이티브 오리진이 정해지면 그때 추가.

### 이번 점검에서 "지금은 안 건드리는 게 맞다"고 판정한 항목 (재검토 불필요, 참고만)
- 3) GIS 로그인 스크립트 로딩(`LoginPage.tsx`): 어댑터가 1개뿐이라 지금 인터페이스를 만들면
  가상의 seam(LANGUAGE.md "one adapter = hypothetical seam" 원칙 위반). 네이티브 착수 시 실제
  네이티브 로그인 라이브러리(Capacitor 등)의 실제 모양을 보고 그때 설계할 것 — 지금 앞당겨서
  만들면 잘못된 모양으로 굳었다가 버려질 위험이 큼.
- 5) 설치 프롬프트/manifest(`RecipeApp.tsx`의 `useInstallPrompt`/`useGikkaDocumentMeta`): 핵심
  로직(인증·등록·냉장고)과 완전히 분리된 pass-through. 네이티브 빌드에선 조건부로 이 훅들만
  끄면 되고, 별도 추상화 불필요.

### 완료된 것 (참고)
- 2) 클립보드 포트: `frontend/src/domain/recipe/data/clipboard.ts` 신설
  (`clipboardReadSupported`/`readClipboardText`), `HomePage.tsx`·`RecipesPage.tsx`가 이 포트를
  쓰도록 교체. `frontend/AGENTS.md`에 등록 완료.
- 4) CORS·베이스 URL(프론트 부분): `data/http.ts`에 `apiUrl()` 추가(현재는 빈 문자열 —
  `VITE_RECIPE_API_BASE_URL`로 나중에 채움) + `request()`가 이를 쓰도록 변경,
  `authRepository.ts`의 직접 fetch 3곳도 `apiUrl()`을 거치도록 통일 + `credentials:'include'`
  추가(이 파일은 401이 정상 응답이라 `http.ts`의 `request()`는 여전히 안 씀 — 의도된 설계이며
  버그 아님). 백엔드 CORS는 일부러 미착수(위 1번과 함께 정할 것). `frontend/AGENTS.md`에
  등록 완료. 테스트(vitest 57개)·빌드 통과 확인.
