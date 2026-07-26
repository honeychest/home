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

### 다음 — 2단계: 배포 파이프라인 (`gikka/` 를 실제로 띄우기)
1. `gikka/Dockerfile` — `springboot/Dockerfile` 을 본뜨되 `gikka.jar`, 힙은 인스턴스 1개 기준.
2. `springboot/docker-compose.yml` 에 `gikka1` 서비스 추가 (**인스턴스 1개로 확정** —
   메모리 여유 때문. 나중에 2개로 늘릴 여지를 위해 이름은 `gikka1`). 포트는 app1/app2(8080·8081),
   nexus 와 겹치지 않게. `env_file` 은 기존 `.env` 재사용 — 환경변수 이름이 분리 전과 같다.
3. `Jenkinsfile` — `Detect Changes` 에 `env.DEPLOY_GIKKA_APP = changed.contains('gikka/')`
   추가(주의: 기존 `DEPLOY_GIKKA` 는 `gikka-extractor/` 용이라 이름이 겹친다) +
   Build/Deploy stage 신설(`chs-gikka` 이미지). `nexus` stage 를 본뜨면 된다.
   인스턴스 1개라 블루그린이 안 되므로 배포 시 수십 초 끊긴다 — 받아들이기로 한 트레이드오프.
4. 배포 후 `gikka1` 컨테이너가 기동·헬스체크 통과하는지만 확인한다. 이 시점에는 nginx 가
   아직 app 으로 보내므로 **트래픽은 안 간다** (양쪽이 같은 DB 를 보지만 워커 중복은
   `claimNext` 의 SKIP LOCKED 가 막는다).

### 3단계: nginx 전환 (사용자가 직접 — 서버 작업)
`/api/recipe/**` 프록시 대상을 app(8080·8081) → gikka1 포트로 변경. 문제 시 원복이 롤백이다.

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
