# HANDOFF — 일회성 작업 인수인계 (완료되면 이 파일을 비운다)

> 용도: recipe 처럼 상설 현황판(progress.html)이 없는 일회성 작업의 세션 간 인계.
> 새 세션(Claude·Codex 무관)은 이 파일이 비어 있지 않으면 먼저 읽고 이어서 작업한다.
> 완료 후에는 이 안내 블록만 남기고 내용을 지운다 (비대화 방지).

## recipe(기까) 앱 분리 — 백엔드 완전 분리 착수

배경: `docs/recipe/CONTEXT.md` "2단계 착수 준비 — 앱 분리(2026-07-24 확정)" 절이 단일 원본.
Play 스토어에 TWA로 배포하려면(비공개 테스트 12명·14일 요건), recipe 배포가 home(트레이딩
대시보드 등)의 배포와 서로 영향을 주면 안 된다는 요구가 있어 백엔드를 완전히 별도 서비스로
분리하기로 확정함. `domain/recipe`는 이미 ArchUnit·전용 DataSource·전용 보안 체인으로 격리돼
있어 추출이 기계적일 것으로 판단.

### 사용자 확인 필요 (최우선, 급함)
- **mac-mini plist 경로 갱신 여부 확인**: 이번 세션에서 `gikka/` → `gikka-extractor/` 폴더명을
  바꿨고 이미 커밋·푸시됨. mac-mini의 `~/Library/LaunchAgents/com.gikka.local-extractor.plist`가
  아직 옛 경로(`.../gikka/server.py`)를 가리키면, Jenkins의 "Deploy Gikka Local" stage가
  `launchctl kickstart -k`를 실행할 때 존재하지 않는 경로라 로컬 추출기가 죽는다(전량 Gemini
  폴백 → 한도 소모 가속). **사용자가 이미 고쳤는지 확인하고, 안 했으면 최우선으로 처리할 것.**

### 다음 세션 최우선 작업 — `gikka/` 신설 (Spring Boot 프로젝트)
1. 저장소 루트에 새 Gradle 프로젝트 `gikka/` 생성(기존 `springboot/`는 건드리지 않고 완전히
   새로 만듦 — 멀티모듈 아님, 독립 프로젝트).
2. `springboot/src/main/java/com/chs/springboot/domain/recipe/**`(및 test) 전체를 새 프로젝트로
   이관. 관련 설정도 함께: `GikkaSecurityConfig`, `GikkaAuthProperties`, `GikkaDataSourceConfig`,
   `CurrentUserConfig`, `application-*.properties`의 `gikka.*` 값 전부.
3. 기존 `springboot/`에서 recipe 관련 코드·`RecipeIsolationArchTest` 제거(더 이상 다른 도메인과
   같은 프로세스에 없으므로 ArchUnit 격리 테스트 자체가 무의미해짐 — 새 프로젝트로 이동하거나
   삭제 검토).
4. `gikka/`용 Dockerfile + `docker-compose.yml`에 `gikka1`/`gikka2` 서비스 신설(기존 `app1`/`app2`
   정의를 참고해 포트만 바꾸는 수준).
5. `Jenkinsfile`에 "Build & Push Gikka Backend"(`gikka/` 변경 감지, `chs-gikka` 이미지) +
   "Deploy Gikka Backend"(`deploy-back-only.sh`와 같은 롤링+헬스체크 패턴, 새 스크립트 또는
   파라미터화) stage 신설 — `nexus` stage들을 그대로 본뜨면 됨.
6. 프론트 격리(별개 작업, 병행 가능): 호스트네임이 `gikka.devcontext.net`이면 `RecipeApp`만
   루트(`/`)에 마운트하고 다른 라우트(트레이드·챗봇·관리자 등)는 등록 자체를 안 하도록
   `frontend/src/app/router/MainRouter.jsx` 진입부에 분기 추가. 완전 별도 빌드 파이프라인은
   만들지 않기로 함(코드 분할이 이미 있어 gikka 방문자는 recipe 청크만 받음).

### 이번 세션에서 이미 끝난 것 (재검토 불필요, 참고만)
- 인증을 쿠키(SameSite=Lax)에서 `Authorization: Bearer` 헤더로 전환 완료 — 오리진 무관하게
  동작(네이티브/TWA 대비). 프론트 `data/tokenStorage.ts`(신규)·`data/http.ts`의 `authHeader()`,
  백엔드 `JwtCurrentUser`·`GikkaAuthController`·`GikkaSecurityConfig`(CORS 자리만 마련,
  `gikka.auth.allowed-origins` 기본 비어있어 지금 동작엔 영향 없음). 테스트·빌드 통과 확인.
- `data/clipboard.ts` 신설(클립보드 포트), `data/http.ts`의 `apiUrl()`(API 오리진 접두사) —
  둘 다 네이티브 전환 대비 후보 2·4 작업.
- GIS 로그인 스크립트(후보 3), 설치 프롬프트/manifest(후보 5)는 "지금은 안 건드리는 게 맞다"로
  판정 — 재검토 불필요(이유는 CONTEXT.md에 남기지 않았으므로 필요하면 이 대화 기록 참고,
  요지는 어댑터가 1개뿐이라 지금 인터페이스를 만들면 가상의 seam이라는 것).
- `gikka/` → `gikka-extractor/` 폴더명 변경(mac-mini 로컬 추출기 호스트 서비스) — `gikka/`를
  새 백엔드 서비스 자리로 비우기 위함. Jenkinsfile·관련 문서 경로 전부 갱신 완료.
- TWA(Trusted Web Activity) 방식 채택, 도메인은 `gikka.devcontext.net`(전용 도메인 구입 보류),
  위치알림(geofencing)은 우선순위 낮아 나중에 Capacitor로 셸 교체 예정(웹 코드 재사용,
  applicationId만 유지하면 스토어 실적 안 끊김) — 전부 CONTEXT.md 새 절에 기록됨.

### 사용자가 직접 해야 하는 것 (서버·콘솔, 코드 아님 — gikka 신설 이후 순서대로)
- DNS: `gikka.devcontext.net` 레코드 추가
- mac-mini nginx: 서브도메인 서버 블록(정적 파일 + `/api/recipe` 프록시, 백엔드 분리 후엔
  `chs-gikka` 업스트림으로)
- Google Cloud Console: OAuth 승인된 오리진에 `https://gikka.devcontext.net` 추가
- Play Console: 비공개 테스트 트랙 생성, 안드로이드 패키지명(applicationId) 결정 + 키스토어 백업
- `gikka.auth.allowed-emails`: 이미 2026-07-20 커밋(`b744f05`)으로 비워둬서 전체 공개 상태 —
  로그인이 막히면 배포 반영 여부부터 확인(코드 문제 아님)
