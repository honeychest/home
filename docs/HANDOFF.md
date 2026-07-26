# HANDOFF — 일회성 작업 인수인계 (완료되면 이 파일을 비운다)

> 용도: recipe 처럼 상설 현황판(progress.html)이 없는 일회성 작업의 세션 간 인계.
> 새 세션(Claude·Codex 무관)은 이 파일이 비어 있지 않으면 먼저 읽고 이어서 작업한다.
> **끝난 항목은 남기지 않고 지운다** (경위는 git 이력에 있다).

## recipe(기까) 앱 분리 — 백엔드 완전 분리
배경·확정 사항은 `docs/recipe/CONTEXT.md` "2단계 착수 준비 — 앱 분리" 절이 단일 원본.
컷오버 방식은 **병행 후 전환**으로 확정 (2026-07-26 사용자 결정): 새 앱을 먼저 띄워
양쪽이 같은 gikka DB 를 보게 두고, nginx 를 돌린 뒤 확인되면 옛 코드를 지운다.
각 단계가 되돌릴 수 있는 것이 이 순서를 고른 이유다.

### 완료 — 1단계: `gikka/` 신설 + 코드 이관 (2026-07-26)
독립 Gradle 프로젝트 `gikka/`, 패키지 `com.chs.gikka`, 메인 `GikkaApplication`.
recipe 메인 61 + 테스트 26 + Flyway 15개 이관, `./gradlew test` 187개 통과,
local 프로파일 실기동으로 Flyway 검증(schema v15 일치)·엔드포인트 4개 200 확인.
**`springboot/` 는 아직 무수정** — recipe 코드가 양쪽에 있고, 서비스는 여전히 app1/app2 가 한다.
규칙·환경 구분은 `gikka/AGENTS.md` 에 정리했다.

#### 병행 기간(1~4단계 사이)의 규칙 — 두 가지를 반드시 지킬 것
1. **recipe 코드 수정은 `gikka/` 에만.** `springboot/domain/recipe/**` 는 곧 지울 사본이다.
   양쪽에 반영하면 4단계 삭제 때 어느 쪽이 최신인지 알 수 없어진다.
2. **gikka DB 마이그레이션을 추가하지 말 것** (V16 이후는 4단계 이후로 미룬다).
   두 앱이 같은 `gikka` DB 에 각자의 Flyway 로 붙어 있는데, 새 파일을 `gikka/` 에만 넣으면
   DB 이력에는 V16 이 적용되고 `springboot/` 의 locations 에는 그 파일이 없다 →
   **다음 백엔드 배포 때 springboot 앱이 "applied migration not resolved locally" 로 기동 실패**한다
   (Flyway 기본 validateOnMigrate=true). 꼭 필요하면 **같은 파일을 양쪽 폴더에 동시에** 넣는다.
   기존 V1~V15 는 양쪽 내용이 같아 안전하다(2026-07-26 실기동으로 schema v15 일치 확인).

### 완료 — 2단계: 배포 파이프라인 (2026-07-26)
- `gikka/Dockerfile` (`gikka.jar`) · `gikka/deploy-gikka.sh` (stop→up→헬스체크 50회×3초)
- `springboot/docker-compose.yml` 에 `gikka1` — 이미지 `chs-gikka`, 컨테이너 `chs-gikka-1`,
  포트 `127.0.0.1:8082:8080`, 힙 `-Xmx512m` / 컨테이너 1024M.
  `<<: *common` 도 `env_file` 도 안 쓴다 — 저 앵커는 MySQL·Rabbit·Kafka·Redis 를 달고 오는데
  gikka 는 그중 무엇도 쓰지 않고, `.env` 통째 주입은 안 쓰는 시크릿까지 컨테이너에 넣는다.
  필요한 7개만 `environment` 에 명시하고 값은 compose 가 같은 폴더 `.env` 에서 보간한다.
- `Jenkinsfile` — `DEPLOY_GIKKA_APP`(`gikka/`) + Build/Deploy Gikka App stage,
  파라미터 `GIKKA_APP_ONLY`. 기존 `DEPLOY_GIKKA` 는 mac-mini launchd 추출기용이라 이름을 갈랐다.

> **이번 푸시에서 gikka stage 는 안 돈다.** Jenkins 는 push 웹훅을 받으면 *그 시점 브랜치의*
> Jenkinsfile 로 파이프라인을 정의한 뒤 Sync Local(git pull)을 돈다 — stage 정의가 처음 들어오는
> 커밋은 옛 정의로 실행된다(2026-07-16 `Deploy Gikka Local` 신설 때 실측). 그래서 **첫 배포는
> `GIKKA_APP_ONLY=true` 로 수동 재기동**해야 한다. 이후 `gikka/` 변경부터는 자동으로 잡힌다.
> 참고: 이번 커밋은 `springboot/` 도 건드리므로(compose·AGENTS.md) 백엔드 재배포는 돈다 —
> springboot 코드는 무수정이라 같은 코드가 롤링으로 다시 뜰 뿐이다.

### 다음 — 3단계: 배포 확인 → nginx 전환
1. **먼저 gikka1 이 떠 있는지 확인**(위 수동 재기동 후). 이 시점엔 nginx 가 아직 app 으로 보내므로
   트래픽은 안 간다 — 양쪽이 같은 DB 를 봐도 워커 중복은 `claimNext` 의 SKIP LOCKED 가 막는다.
2. 확인되면 `chs/server/nginx/devcontext.conf` 를 고친다. **아직 안 고쳐 뒀다** — 미리 커밋해 두면
   서버가 pull·reload 하는 순간 gikka1 이 없는 상태에서 recipe 가 502 가 되기 때문이다.
   고칠 곳 (upstream 은 단일이라 `$sticky_backend`·`SRV_ID` 쿠키가 필요 없다):
   - `upstream chs_gikka { server 127.0.0.1:8082 max_fails=2 fail_timeout=3s; }` 추가
   - 기존 `location ^~ /api/recipe/llm/` 의 `proxy_pass` 를 `http://chs_gikka` 로
     (read_timeout 120s·`proxy_next_upstream off` 는 그대로 — 사고 계기가 여전히 유효하다)
   - 새 `location ^~ /api/recipe/` 블록 추가, `proxy_pass http://chs_gikka`.
     **`location /api` 보다 먼저 매칭돼야 한다**(`^~` 라 접두 매칭 우선). read_timeout 은 지금과
     같은 15s 로 시작한다 — X다운로드 resolve 가 그 아래에서 이미 돌고 있다.
3. `nginx -t` → `nginx -s reload` → 기까 앱에서 보관함·냉장고·추천·등록 1건 확인.
   **롤백 = conf 원복 후 reload.** 앱은 건드리지 않는다(옛 코드가 app1/app2 에 아직 살아 있다).

### 4단계: `springboot/` 에서 recipe 제거
3단계가 실사용으로 확인된 뒤에만. `springboot/src/**/domain/recipe/**`(+test) 삭제,
`application.properties`·`application-{local,prod}.properties` 의 `gikka.*` 삭제,
`RecipeIsolationArchTest` 삭제(안쪽 규칙 둘은 `gikka/GikkaArchitectureTest` 로 이미 옮겨졌다),
recipe 전용이던 의존성 정리(flyway-database-postgresql 은 챗봇 pgvector 도 쓰는지 확인 후 판단),
`springboot/AGENTS.md` 의 recipe 관련 서술 정리.

### 5단계 (병행 가능): 프론트 격리
호스트네임이 `gikka.devcontext.net` 이면 `MainRouter.jsx` 가 `RecipeApp` 만 루트에 마운트하고
다른 라우트는 등록하지 않는다. 별도 빌드 파이프라인은 만들지 않는다.

### 사용자가 직접 해야 하는 것 (서버·콘솔)
- DNS `gikka.devcontext.net` 레코드 / mac-mini nginx 서브도메인 서버 블록(정적 + `/api/recipe` 프록시)
- Google Cloud Console OAuth 승인된 오리진에 `https://gikka.devcontext.net` 추가
- Play Console 비공개 테스트 트랙 (applicationId `net.devcontext.gikka`, Play App Signing 사용).
  `assetlinks.json` 의 SHA-256 은 업로드 키가 아니라 **Play Console 이 발급한 앱 서명 키** 지문 —
  틀리면 앱이 죽지 않고 주소창 보이는 브라우저 모드로 조용히 떨어진다.
