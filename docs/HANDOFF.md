# HANDOFF — 일회성 작업 인수인계 (완료되면 이 파일을 비운다)

> 용도: recipe 처럼 상설 현황판(progress.html)이 없는 일회성 작업의 세션 간 인계.
> 새 세션(Claude·Codex 무관)은 이 파일이 비어 있지 않으면 먼저 읽고 이어서 작업한다.
> **끝난 항목은 남기지 않고 지운다** (경위는 git 이력에 있다).

## recipe(기까) 앱 분리 — 앱 출시(AAB/APK)까지 남은 작업
배경·확정 사항은 `docs/recipe/CONTEXT.md` 18절이 단일 원본.
**목표는 "웹 기능을 옮기기"가 아니라 "기까를 별도 앱으로 출시하기"** 다 — 아래 순서가 그 기준으로 잡혀 있다.

### 완료 (2026-07-26)
- **백엔드**: `honeychest/gikka` 별도 저장소(패키지 `com.chs.gikka`). 서버에 이미 떠 있다
  (`chs-gikka-1`, 8082). 자체 Jenkinsfile(Multibranch) · docker-compose · deploy-gikka.sh.
- **앱 전용 도메인**: `gikka.devcontext.net` → `/api/recipe/**` 가 `chs_gikka`(8082)로 간다
  (`chs/server/nginx/gikka.conf`). 즉 **앱이 쓸 백엔드는 이미 연결돼 있다.**
- **프론트 사본**: lab 의 `frontend/src/domain/recipe/**`(55개) + `public/recipe/**` 를
  gikka 저장소 `frontend/` 로 복사하고 독립 빌드를 세웠다 — `npm run test` 65개 · `typecheck` ·
  `build` 통과. 산출물 323KB(gzip 102KB), cesium 0.
  (lab 번들은 `index-*.js` 1.8MB + `Cesium.js` 5.7MB 를 앱 사용자에게도 내려보내고 있었다.
  `vite-plugin-cesium` 이 조건 없이 index.html 에 주입하므로 lazy 화로는 안 걷힌다 — 이것이
  CONTEXT 18절의 "lazy 화로 충분"을 뒤집고 빌드를 가른 이유다.)

### 확정된 결정 (이번에 정한 것 — 다시 논의하지 말 것)
1. **프론트는 gikka 저장소 안 `frontend/`** (별도 저장소로 또 가르지 않는다). 프론트와 백엔드는
   같은 앱이라 같이 배포되는 편이 정상이고, 저장소를 가르면 API 계약 변경이 두 커밋으로 쪼개진다.
2. **웹 `devcontext.net/recipe` 는 유지하지 않는다.** 앱 배포가 확인되면 폐지한다 →
   그래서 `devcontext.conf` 의 recipe 프록시를 `chs_gikka` 로 **전환하는 작업은 필요 없다**.
   대신 아래 4단계에서 `location ^~ /api/recipe/llm/` 블록을 **지운다**.
3. TWA 셸(Bubblewrap) 작업은 사용자가 앱 개발 첫 경험이라 **3단계에서 함께 결정**한다
   (키스토어를 어디서 만들고 어디에 백업할지가 같이 걸린다).

### 병행 기간 규칙 — 지금 사본이 둘이다
1. **recipe 프론트 수정은 gikka 저장소에서만.** `frontend/src/domain/recipe/**` 는 곧 지울 사본이다.
2. **recipe 백엔드 수정도 gikka 저장소에서만.** `springboot/domain/recipe/**` 도 같다.
3. **gikka DB 마이그레이션(V16 이후) 추가 금지.** 두 앱이 같은 `gikka` DB 에 각자의 Flyway 로
   붙어 있어서, 새 파일을 gikka 저장소에만 넣으면 DB 이력에는 적용되고 이쪽 locations 에는 없다
   → **다음 백엔드 배포 때 springboot 앱이 "applied migration not resolved locally" 로 기동 실패**
   (validateOnMigrate 기본 true). 꼭 필요하면 같은 파일을 양쪽에 동시에 넣는다.

### 1단계: gikka 프론트를 서버에 올린다 (다음 작업)
- gikka 저장소에 정적 배포 스크립트 — lab `frontend/deploy-front-only.sh` 패턴(releases 디렉터리 +
  심링크 교체, 롤백이 심링크 하나) 을 gikka 용으로 작성.
  **경로 확정**: `docker-volumes/nginx/gikka/{releases,dist,previous}`
  (lab 은 `docker-volumes/nginx/{releases,dist}` — 한 단계 아래로 넣어 섞이지 않게 한다)
- gikka `Jenkinsfile` 에 프론트 stage 추가.
- `chs/server/nginx/gikka.conf` 의 `location /` root 를 새 dist 로 교체 (**이 저장소 파일** — 서버
  라우팅 전체를 lab 이 소유한다). 이걸 바꾸기 전까지 앱 도메인은 lab dist(cesium 포함)를 계속 준다.

### 2단계: 실기 확인
`gikka.devcontext.net` 에서 로그인·보관함·냉장고·추천·등록 1건·사전·X다운로드.
글꼴(Pretendard)·여백·하단 탭 안전영역을 **iOS 실기기로** 확인 — 리셋 CSS 를 lab 의 tailwind
preflight 에서 `src/base.css` 로 갈아탔으므로 여백이 어긋날 수 있는 지점이 여기다.

### 3단계: 앱 패키징 (TWA)
Bubblewrap 프로젝트 위치·키스토어 생성·백업 → AAB → Play Console 비공개 테스트 업로드 →
**Play 앱 서명 키 SHA-256** 확보 → `assetlinks.json` 을 그 지문으로 채워
`https://gikka.devcontext.net/.well-known/assetlinks.json` 로 서빙 → 앱에서 주소창이 안 보이는지 확인.
(지문은 업로드 키가 아니다. 틀리면 앱이 죽지 않고 브라우저 모드로 조용히 떨어진다.)
남은 콘솔 작업: Google OAuth 승인된 오리진에 `https://gikka.devcontext.net` 추가.

### 4단계: lab 청소 (3단계가 실사용으로 확인된 뒤에만)
- `frontend/src/domain/recipe/**`(55개) · `frontend/public/recipe/**` 삭제
- recipe 참조 3곳 정리 — `app/router/MainRouter.jsx`(라우트·`GikkaHostGuard`·챗봇 숨김 접두어),
  `cssLayoutGuard.test.ts`(recipe 규칙 부분), `shared/ui/samples/visualSamples.js`(카탈로그 주석)
- `springboot/src/**/domain/recipe/**`(+test, 합 88개) 삭제, `application*.properties` 의 `gikka.*` 삭제,
  `RecipeIsolationArchTest` 삭제, recipe 전용 의존성 정리
  (`flyway-database-postgresql` 은 챗봇 pgvector 도 쓰는지 확인 후 판단)
- `chs/server/nginx/devcontext.conf` — `upstream chs_gikka`(21행)는 `gikka.conf` 가 쓰므로 **남기고**,
  `location ^~ /api/recipe/llm/` 블록만 삭제
- 문서: `docs/recipe/CONTEXT.md` 18절(프론트 격리·lazy 서술 → 별도 빌드로 갱신) · 19절(격리 규율의
  "프론트는 src/domain/recipe" 서술), `frontend/AGENTS.md` 의 recipe 서술,
  `springboot/AGENTS.md` 의 recipe 패턴 표, `frontend/.../backendPatterns.js` 의 `[gikka 저장소]` 표기
