# HANDOFF — 일회성 작업 인수인계 (완료되면 이 파일을 비운다)

> 용도: recipe 처럼 상설 현황판(progress.html)이 없는 일회성 작업의 세션 간 인계.
> 새 세션(Claude·Codex 무관)은 이 파일이 비어 있지 않으면 먼저 읽고 이어서 작업한다.
> **끝난 항목은 남기지 않고 지운다** (경위는 git 이력에 있다).

## recipe(기까) 백엔드 분리 — 전환만 남음
배경·확정 사항은 `docs/recipe/CONTEXT.md` 18절이 단일 원본.

### 완료 (2026-07-26)
recipe 백엔드가 **`honeychest/gikka` 별도 저장소**로 나갔다. 패키지 `com.chs.gikka`,
메인 61 + 테스트 26 + Flyway 15개, `./gradlew test` 187개 통과, local 실기동으로
Flyway schema v15 일치·엔드포인트 4개 200 확인. 자체 Jenkinsfile · docker-compose(gikka1,
`chs-gikka` 이미지, 포트 8082) · deploy-gikka.sh 를 갖췄고, lab 에서는 gikka 관련
배포 설정을 전부 걷어냈다(Jenkinsfile stage · compose gikka1).

**로컬 체크아웃**: `C:\Users\Tissue\IdeaProjects\gikka` (lab 과 같은 레벨)

### 지금 상태 — 왜 아직 안 끝났나
```
사용자 → nginx → app1/app2 (springboot/domain/recipe)   ← 여전히 이쪽이 서비스 중
                 chs-gikka-1 (8082)                      ← 아직 서버에 안 뜸
```
recipe 코드가 **양쪽에 다 있다.** 이건 의도된 상태다 — 새 것을 띄워 충분히 확인한 뒤
nginx 주소만 바꾸고, 그 다음에 옛 것을 지운다. 롤백이 conf 원복 하나로 끝나는 구조.

### 병행 기간 규칙 — 두 가지를 반드시 지킬 것
1. **recipe 백엔드 수정은 gikka 저장소에만.** `springboot/domain/recipe/**` 는 곧 지울 사본이다.
   양쪽에 반영하면 삭제 때 어느 쪽이 최신인지 알 수 없어진다.
2. **gikka DB 마이그레이션을 추가하지 말 것** (V16 이후는 아래 3단계 이후로 미룬다).
   두 앱이 같은 `gikka` DB 에 각자의 Flyway 로 붙어 있어서, 새 파일을 gikka 저장소에만 넣으면
   DB 이력에는 적용되고 이쪽 locations 에는 없다 → **다음 백엔드 배포 때 springboot 앱이
   "applied migration not resolved locally" 로 기동 실패**한다(validateOnMigrate 기본 true).
   꼭 필요하면 **같은 파일을 양쪽에 동시에** 넣는다. 기존 V1~V15 는 내용이 같아 안전하다.

### 1단계: gikka 저장소 최초 배포 (서버 작업)
절차는 **gikka 저장소의 `serverAgent.md` §2** 에 있다(체크아웃·`.env`·Jenkins job·웹훅).
이 시점에도 nginx 는 app1/app2 를 가리키므로 트래픽이 안 간다 — 마음껏 확인해도 무영향.

### 2단계: 검증
기까 앱에서 보관함·냉장고·추천·등록 1건·사전·X다운로드. 8082 로 직접 호출해도 된다.

### 3단계: nginx 전환 ← 유일한 스위치
`chs/server/nginx/devcontext.conf` (**이 저장소 소유** — 서버 전체 라우팅이라 gikka 로 안 넘겼다):
- `upstream chs_gikka { server 127.0.0.1:8082 max_fails=2 fail_timeout=3s; }` 추가
  (upstream 단일이라 `$sticky_backend`·`SRV_ID` 쿠키 불필요)
- 기존 `location ^~ /api/recipe/llm/` 의 `proxy_pass` → `http://chs_gikka`
  (read_timeout 120s·`proxy_next_upstream off` 유지 — 재시도하면 같은 Gemini 호출이 한 번 더
  나가 무료 한도를 두 배로 태운다. 이 블록이 생긴 계기가 그것이다)
- 새 `location ^~ /api/recipe/` 추가 → `proxy_pass http://chs_gikka`.
  `location /api` 보다 먼저 매칭돼야 한다(`^~` 접두 우선). read_timeout 은 지금과 같은 15s

`nginx -t` → `nginx -s reload`. **롤백 = conf 원복 후 reload.**

### 4단계: lab 에서 recipe 삭제 (3단계가 실사용으로 확인된 뒤에만)
- `springboot/src/**/domain/recipe/**`(+test) 삭제
- `application.properties`·`application-{local,prod}.properties` 의 `gikka.*` 삭제
- `RecipeIsolationArchTest` 삭제 (안쪽 규칙 둘은 gikka 저장소 `GikkaArchitectureTest` 로 옮겨짐)
- recipe 전용 의존성 정리 — `flyway-database-postgresql` 은 챗봇 pgvector 도 쓰는지 확인 후 판단
- `springboot/AGENTS.md` 의 recipe 서술·패턴 표 정리, `frontend/.../backendPatterns.js` 의
  `[gikka 저장소]` 경로 표기 확인
- `docs/recipe/CONTEXT.md` 19절(격리 규율)에서 "백엔드는 domain/recipe 안에서만" 갱신

### 5단계 (병행 가능): 프론트 번들 격리
`MainRouter.jsx` 는 이미 `gikka.devcontext.net` 이면 `/recipe` 외 경로를 리다이렉트한다
(64~71행 — CONTEXT 18절의 "미구현" 서술은 낡았다). 다만 **정적 import 23개가 그대로
번들에 실려** gikka 도메인 사용자도 `index-*.js` 1.8M 을 받는다(RecipeApp 청크는 89K).
lazy 화로 가르는 것이 남은 일. TWA 착수 전에 하는 것이 좋다.

### 사용자가 직접 해야 하는 것 (서버·콘솔)
- DNS `gikka.devcontext.net` 레코드 / mac-mini nginx 서브도메인 서버 블록(정적 + `/api/recipe` 프록시)
- Google Cloud Console OAuth 승인된 오리진에 `https://gikka.devcontext.net` 추가
- Play Console 비공개 테스트 트랙 (applicationId `net.devcontext.gikka`, Play App Signing 사용).
  `assetlinks.json` 의 SHA-256 은 업로드 키가 아니라 **Play Console 이 발급한 앱 서명 키** 지문 —
  틀리면 앱이 죽지 않고 주소창 보이는 브라우저 모드로 조용히 떨어진다.
