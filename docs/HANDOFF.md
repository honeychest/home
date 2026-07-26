# HANDOFF — 일회성 작업 인수인계 (완료되면 이 파일을 비운다)

> 용도: recipe 처럼 상설 현황판(progress.html)이 없는 일회성 작업의 세션 간 인계.
> 새 세션(Claude·Codex 무관)은 이 파일이 비어 있지 않으면 먼저 읽고 이어서 작업한다.
> **끝난 항목은 남기지 않고 지운다** (경위는 git 이력에 있다).

## recipe(기까) 앱 분리 — 백엔드 완전 분리
배경·확정 사항은 `docs/recipe/CONTEXT.md` "2단계 착수 준비 — 앱 분리" 절이 단일 원본.

### 급함 — 사용자 확인 필요
- **mac-mini plist 경로**: `gikka/` → `gikka-extractor/` 폴더명 변경이 이미 배포됨.
  `~/Library/LaunchAgents/com.gikka.local-extractor.plist` 가 옛 경로를 가리키면 Jenkins 의
  "Deploy Gikka Local" stage 에서 로컬 추출기가 죽는다(전량 Gemini 폴백 → 한도 소모 가속).

### 다음 세션 최우선 — `gikka/` 신설 (독립 Spring Boot 프로젝트, 현재 빈 폴더)
1. 저장소 루트에 새 Gradle 프로젝트 `gikka/` (멀티모듈 아님, `springboot/` 는 건드리지 않음).
2. `springboot/.../domain/recipe/**`(+test) 전체 이관. 설정도 함께 — `GikkaSecurityConfig`,
   `GikkaAuthProperties`, `GikkaDataSourceConfig`, `CurrentUserConfig`, `gikka.*` 프로퍼티 전부.
3. 기존 `springboot/` 에서 recipe 코드 제거. `RecipeIsolationArchTest` 는 **통째로 지우지 말 것** —
   바깥 담(규칙 1·2)만 무의미해지고, **안쪽 방향 규칙 둘은 새 프로젝트에서 그대로 살려야 한다**:
   `dictionary ↛ registration` · `external ↛ recipe 의 다른 패키지`(2026-07-26 신설, CONTEXT 19절 12번).
4. `gikka/` Dockerfile + `docker-compose.yml` 에 `gikka1`/`gikka2` 서비스(기존 `app1`/`app2` 본떠 포트만 변경).
5. `Jenkinsfile` 에 Build/Deploy stage 신설 — `nexus` stage 를 그대로 본뜨면 된다(`chs-gikka` 이미지).
6. (병행 가능) 프론트 격리: 호스트네임이 `gikka.devcontext.net` 이면 `MainRouter.jsx` 가 `RecipeApp` 만
   루트에 마운트하고 다른 라우트는 등록하지 않는다. 별도 빌드 파이프라인은 만들지 않는다.

### 사용자가 직접 해야 하는 것 (서버·콘솔, gikka 신설 이후 순서대로)
- DNS `gikka.devcontext.net` 레코드 / mac-mini nginx 서브도메인 서버 블록(정적 + `/api/recipe` 프록시)
- Google Cloud Console OAuth 승인된 오리진에 `https://gikka.devcontext.net` 추가
- Play Console 비공개 테스트 트랙 (applicationId `net.devcontext.gikka`, Play App Signing 사용).
  `assetlinks.json` 의 SHA-256 은 업로드 키가 아니라 **Play Console 이 발급한 앱 서명 키** 지문 —
  틀리면 앱이 죽지 않고 주소창 보이는 브라우저 모드로 조용히 떨어진다.
