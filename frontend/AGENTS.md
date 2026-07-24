# frontend 작업 규칙 (모든 AI 에이전트 공통)

이 폴더의 파일을 수정하기 전에 읽는다. 절차 규칙(승인·커밋·점검)은 `/chs/chs-rules.md`.

## 가져다 쓸 것 — 같은 일을 하는 코드를 새로 만들면 그게 결함이다
- 화면 조작(추가·삭제·수정) 실행기: `src/domain/recipe/page/useMutation.ts`
  (실패 문구·연타 방지·재동기화). 화면마다 자기식 try/catch 를 만들지 말 것.
  경계: 사용자 버튼 조작용이다. 화면 진입 시 1회 자동 실행 흐름(연타 없음)은
  직접 catch 허용 — 모범: `ShareTargetPage.tsx` (결함 아님, 고치지 말 것).
- 공용 훅·컴포넌트가 다른 도메인에서 필요하면 import 하지 말고 그 도메인 안으로
  복사해 소유한다 (도메인 격리 — springboot 의 recipe 격리 규칙과 동일 사상).
- 확인창: `src/domain/recipe/ui/RcpConfirm.tsx` — `window.confirm`(시스템 창) 금지(2026-07-19
  확정, 디자인·어투 밖). 확인이 필요한 동작은 이 다이얼로그로. 파괴적 동작(삭제류)은 `danger`.
- 실패 문구 표시 줄: `src/domain/recipe/ui/RcpInlineError.tsx` (조작 실패 = useMutation.error).
- 조회 실패 + 다시 시도 블록: `src/domain/recipe/ui/RcpLoadError.tsx` (useQuery 의 error·reload 를
  그대로 전달 — RcpInlineError 의 짝). 화면 안에서 `.rcp-shell-status` 를 쓰지 말 것 —
  그건 셸(RecipeApp) 전용 100dvh 라 화면 안에 넣으면 화면 하나만큼 부풀고(냉장고·보관함·모니터),
  `overflow:hidden` 인 추천 화면에선 잘렸다 (2026-07-16 점검에서 4곳 전부 교정).
- 목록이 세로로 길어지는 화면의 주 동작 버튼: `src/domain/recipe/ui/RcpFab.tsx` (떠 있는 우하단).
  목록 끝에 `.rcp-btn-full` 을 놓지 말 것 — 항목이 쌓일수록 스크롤 바닥으로 밀려나 못 찾는다
  (2026-07-16 보관함 실사용 제보). 쓰는 화면에 `.rcp-screen-with-fab` 을 함께 건다.
  경계: 냉장고처럼 내용이 세로로 안 늘어나는 화면(선반=가로 스크롤)은 지금의 목록 아래
  전체폭 버튼이 맞다 — 바꾸지 말 것 (2026-07-09 확정).
- 영상/재생목록 등록 분기: `src/domain/recipe/data/registerLink.ts` (영상 우선 규칙의 단일 원본).
- 클립보드 읽기: `src/domain/recipe/data/clipboard.ts` (`clipboardReadSupported`/`readClipboardText`).
  `navigator.clipboard` 직접 호출·기능감지를 화면에 새로 쓰지 말 것 — 실패 시 조용히 무시할지
  문구를 보여줄지는 이 모듈이 아니라 호출하는 화면이 정한다(에러 계약과 동일 사상).
- 도메인 판정(순수 로직)은 화면이 아니라 data/ 순수 모듈 + vitest 로.
  모범: `data/fridgeShelves.ts`, `data/videoUrl.ts`, `data/registerLink.ts`.
- API 호출은 도메인 저장소 인터페이스(`data/*Repository.ts`)를 거친다. 화면에서 fetch 직접 호출 금지.
  저장소가 `http.ts`의 `request()`를 못 쓰는 특수한 이유(예: 401을 정상 응답으로 다뤄야 하는
  `authRepository.me()`)로 fetch를 직접 부를 때도 경로는 반드시 `http.ts`의 `apiUrl()`을 거친다
  (네이티브 전환 대비 — API 오리진 접두사를 한 곳에서만 바꾸면 되게).
- 인증 토큰 저장: `src/domain/recipe/data/tokenStorage.ts` (`getToken`/`setToken`/`clearToken`).
  세션은 쿠키가 아니라 Authorization: Bearer 헤더(2026-07-2x 전환, 네이티브 대비) — 헤더 조립은
  `http.ts`의 `authHeader()`가 이 포트를 감싸 담당하므로 화면·저장소는 토큰을 직접 만지지 않는다.
- 목록 조회 실행기: `src/domain/recipe/page/useQuery.ts` — "3상태"(data=null 첫 로딩 /
  error / 다시 시도)를 화면마다 손으로 만들지 말 것. `{ data, error, failure, setData,
  reload, refresh }` 제공. reload=실패 시 문구, refresh=조용한 재조회(폴링용),
  failure=원인 그대로(원인별 분기용, 예: MonitorPage 의 403 → 접근 거부 화면),
  setData=낙관적 업데이트. 모범: `RecommendPage.tsx`(최소), `MonitorPage.tsx`(폴링+403).
  (2026-07-15 신설 — 이 항목은 원래 "모범: RecipesPage 를 복제하라"였고, 그 복제가 실제로
  RecipesPage 폴링 버그를 낳았다. 복제 대신 이 훅을 쓴다.)
- 상태·진행 효과(깜빡임·스핀·스캔 등): `src/shared/ui/samples` 효과 카탈로그에서
  먼저 고른다 (admin > "UI 샘플" 카드에서 실물 열람. 키 예: sample_live_spinner).
  없으면 새 효과를 카탈로그에 등록한 뒤 사용 — 화면 CSS 에 일회성 keyframes 를 만들면 결함.
  recipe 처럼 자체 토큰을 쓰는 도메인은 직접 import 하지 말고 참고·복사로 자기 방식 적용.
- 적립 규칙: 이번 작업에서 2곳 이상 쓰일 만한 공용 요소(훅·컴포넌트·효과)를 만들었으면
  커밋 전에 이 절에 등록한다 (효과는 카탈로그에도). 쓸수록 라이브러리가 커져야 정상.

## 금지
- **키보드 안전 지대 위반** — 포커스형 입력창 **아래**에 중요한 정보(추천·안내·결과)를 두지 말 것.
  모바일 키보드 + OS 자동입력 툴바(삼성 Pass 등) + 키보드 추천 단어 줄이 화면 아래를 몇 층으로
  덮는지는 기기마다 달라 테스트로 못 잡는다 — 입력창보다 위는 모든 브라우저가 포커스 시 보이게
  밀어 올려주므로 구조적으로 안전 (2026-07-19 냉장고 추가 시트 삼성폰 실기기 확인에서 확정).
- 서버 오류의 `e.message` 를 화면에 그대로 노출 — 실패 문구는 화면이 정한 고정
  한국어 문구로 (에러 계약). LoginPage·RecipeApp 은 공용화 이전 코드라 위반 상태 —
  모방하지 말 것 (수리는 별도 승인 시).
- 화면 코드에 임의 색상값·크기 직접 입력 (recipe 는 tokens.css 변수와 ui/ 킷만).
- 문구와 데이터의 하드코딩 연결(`"재료 " + n + "개"` 식) — 모듈 레벨 템플릿 헬퍼로.
- 고정 너비 (긴 글자 대비 — min-height 만). 탭 가능한 요소는 터치 44px 확보.

## 도메인별
| 영역 | 따를 것 |
|---|---|
| `src/domain/recipe/**` | `docs/recipe/PLAYBOOK.md` 공통 규칙 + "가져다 쓸 것" 절 — 필수 |

## 검증
- `npm run test`(vitest) + `npm run build` 통과 후 보고. 새 공용 컴포넌트는
  `/recipe/styleguide` 등록 + 긴 글자 스트레스 케이스 추가가 의무.
