# frontend 작업 규칙 (모든 AI 에이전트 공통)

이 폴더의 파일을 수정하기 전에 읽는다. 절차 규칙(승인·커밋·점검)은 `/chs/chs-rules.md`.

## 가져다 쓸 것 — 같은 일을 하는 코드를 새로 만들면 그게 결함이다
- 화면 조작(추가·삭제·수정) 실행기: `src/domain/recipe/page/useMutation.ts`
  (실패 문구·연타 방지·재동기화). 화면마다 자기식 try/catch 를 만들지 말 것.
  경계: 사용자 버튼 조작용이다. 화면 진입 시 1회 자동 실행 흐름(연타 없음)은
  직접 catch 허용 — 모범: `ShareTargetPage.tsx` (결함 아님, 고치지 말 것).
- 공용 훅·컴포넌트가 다른 도메인에서 필요하면 import 하지 말고 그 도메인 안으로
  복사해 소유한다 (도메인 격리 — springboot 의 recipe 격리 규칙과 동일 사상).
- 실패 문구 표시 줄: `src/domain/recipe/ui/RcpInlineError.tsx`.
- 영상/재생목록 등록 분기: `src/domain/recipe/data/registerLink.ts` (영상 우선 규칙의 단일 원본).
- 도메인 판정(순수 로직)은 화면이 아니라 data/ 순수 모듈 + vitest 로.
  모범: `data/fridgeShelves.ts`, `data/videoUrl.ts`, `data/registerLink.ts`.
- API 호출은 도메인 저장소 인터페이스(`data/*Repository.ts`)를 거친다. 화면에서 fetch 직접 호출 금지.
- 목록 화면 3상태: `items: T[] | null`(null=첫 로딩) + loadError + 다시 시도 버튼.
  모범: `src/domain/recipe/page/RecipesPage.tsx`.
- 상태·진행 효과(깜빡임·스핀·스캔 등): `src/shared/ui/samples` 효과 카탈로그에서
  먼저 고른다 (admin > "UI 샘플" 카드에서 실물 열람. 키 예: sample_live_spinner).
  없으면 새 효과를 카탈로그에 등록한 뒤 사용 — 화면 CSS 에 일회성 keyframes 를 만들면 결함.
  recipe 처럼 자체 토큰을 쓰는 도메인은 직접 import 하지 말고 참고·복사로 자기 방식 적용.
- 적립 규칙: 이번 작업에서 2곳 이상 쓰일 만한 공용 요소(훅·컴포넌트·효과)를 만들었으면
  커밋 전에 이 절에 등록한다 (효과는 카탈로그에도). 쓸수록 라이브러리가 커져야 정상.

## 금지
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
