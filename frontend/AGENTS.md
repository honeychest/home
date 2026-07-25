# frontend 작업 규칙 (모든 AI 에이전트 공통)

이 폴더의 파일을 수정하기 전에 읽는다. 절차 규칙(승인·커밋·점검)은 `/chs/chs-rules.md`.
여기엔 **규칙만** 적는다 — 왜 그렇게 정했는지(경위·근거)는 각 실물 코드의 주석에 있다.

## 가져다 쓸 것 — 같은 일을 하는 코드를 새로 만들면 그게 결함이다
경로는 `src/domain/recipe/` 기준.

| 필요한 것 | 쓸 것 | 주의 |
|---|---|---|
| 조작(추가·삭제·수정) 실행기 | `page/useMutation.ts` | 화면마다 try/catch 금지. 진입 시 1회 자동 실행만 직접 catch 허용(`ShareTargetPage.tsx`) |
| 목록 조회 3상태 | `page/useQuery.ts` | 첫 로딩·실패·재시도를 손으로 만들지 말 것. 모범 `RecommendPage`(최소)·`MonitorPage`(폴링+403) |
| 조작 실패 문구 | `ui/RcpInlineError.tsx` | |
| 조회 실패 + 다시 시도 | `ui/RcpLoadError.tsx` | 화면 안에서 `.rcp-shell-status`(셸 전용) 쓰지 말 것 |
| 확인창 | `ui/RcpConfirm.tsx` | `window.confirm` 금지. 삭제류는 `danger` |
| 세로로 긴 목록의 주 동작 | `ui/RcpFab.tsx` + `.rcp-screen-with-fab` | 목록 끝 `.rcp-btn-full` 금지(스크롤 바닥으로 밀림). 냉장고처럼 안 늘어나는 화면은 전체폭 버튼 유지 |
| API 호출 | `data/*Repository.ts` | 화면에서 fetch 직접 호출 금지. 저장소가 fetch 를 직접 쓸 때도 경로는 `http.ts`의 `apiUrl()` 경유 |
| 인증 토큰 | `data/tokenStorage.ts` | `getToken()` 은 반드시 동기 유지. 셸의 `storageReady` 게이트 지우지 말 것 |
| 실행 환경 판정 | `data/platform.ts` | 화면에서 `window.location`·`navigator.*`·`localStorage` 직접 호출 금지. 새 환경 API 는 여기 추가 |
| 클립보드 | `data/clipboard.ts` | 실패 시 무시할지 문구를 낼지는 호출하는 화면이 정한다 |
| 구글 로그인(GIS) | `data/googleSignIn.ts` | 화면은 버튼 위치와 ID 토큰만 안다 |
| 영상/재생목록 등록 분기 | `data/registerLink.ts` | 영상 우선 규칙의 단일 원본 |
| 상태·진행 효과 | `src/shared/ui/samples` 카탈로그 | 화면 CSS 에 일회성 keyframes 금지. recipe 는 import 말고 복사해 소유 |

- 도메인 판정(순수 로직)은 화면이 아니라 `data/` 순수 모듈 + vitest. 모범 `fridgeShelves.ts`·`videoUrl.ts`.
- 공용 코드가 다른 도메인에 필요하면 import 하지 말고 그 도메인 안으로 복사해 소유한다(도메인 격리).
- 적립: 2곳 이상 쓰일 공용 요소를 만들었으면 커밋 전에 이 표에 등록한다(효과는 카탈로그에도).

## 금지
- 키보드 안전 지대 위반 — 포커스형 입력창 **아래**에 중요한 정보를 두지 말 것(덮이는 높이가 기기마다 달라 테스트로 못 잡음).
- 서버 오류 `e.message` 를 화면에 노출 — 문구는 화면이 정한 고정 한국어로. `LoginPage`·`RecipeApp` 은 위반 상태이니 모방 금지.
- 화면 코드에 임의 색상값·크기 (recipe 는 tokens.css 변수와 ui/ 킷만).
- 문구와 데이터의 하드코딩 연결(`"재료 " + n + "개"` 식) — 모듈 레벨 템플릿 헬퍼로.
- 고정 너비 (min-height 만). 탭 가능한 요소는 터치 44px 확보.

## iOS(WebKit) 함정 — 화면 치수·스크롤·안전영역을 건드릴 때
iOS 는 사파리·크롬·PWA 가 전부 WebKit 이다 — 브라우저를 바꿔도 안 되면 계정·서버가 아니라 엔진 문제다.
- `aspect-ratio` 로 치수 역산 금지, 폭·높이 중 하나 명시 — `.rcp-coverflow-card` (가드 `src/cssLayoutGuard.test.ts`)
- 문서 탄성 스크롤(달랑거림)은 html·body 잠금으로만 멈춘다 — `data/platform.ts` `lockDocumentScroll()`
- 하단 안전영역은 height 에 더한다(border-box 라 padding 만 주면 내용 자리를 먹음) — `.rcp-tab-bar`
- `<svg>` 에 padding 으로 배경 원 만들지 말 것(원은 일반 요소가 그린다) — `.rcp-install-replay-badge`
- 가로 스크롤의 끝쪽 여백은 padding 말고 마지막 항목의 margin — `RcpCoverflow.setup()`

## 네이티브 전환 대비 (TWA → Capacitor 예정)
기준 하나: **지금 한다 = 나중에 하면 여러 파일로 번지는 것 / 나중에 한다 = 그때도 파일 하나면 되는 것.**
미룬 것: Share Target 진입점, `'/recipe'` 경로 하드코딩, HomePage X다운로드 blob 저장, 안드로이드 하드웨어 뒤로가기.

## 도메인별
| 영역 | 따를 것 |
|---|---|
| `src/domain/recipe/**` | `docs/recipe/PLAYBOOK.md` 공통 규칙 + 위 "가져다 쓸 것" |

## 검증
- `npm run test` + `npm run build` 통과 후 보고. 새 공용 컴포넌트는 `/recipe/styleguide` 등록 + 긴 글자 케이스 추가가 의무.
- 치수·스크롤·안전영역을 건드렸으면 **iOS 실기기로 확인**(크롬·PC 로는 못 잡는다): 세로로 안 움직이는가 / 탭 아래 빈 띠 / 마지막 항목 가림.
- 가드 테스트가 실패하면 예외를 추가하지 말고 코드를 옮긴다 — `cssLayoutGuard.test.ts`(치수), `domain/recipe/platformIsolation.test.ts`(환경 API 직접 호출).
