# HANDOFF — 일회성 작업 인수인계 (완료되면 이 파일을 비운다)

> 용도: recipe 처럼 상설 현황판(progress.html)이 없는 일회성 작업의 세션 간 인계.
> 새 세션(Claude·Codex 무관)은 이 파일이 비어 있지 않으면 먼저 읽고 이어서 작업한다.
> **끝난 항목은 남기지 않고 지운다** (경위는 git 이력에 있다).

## recipe(기까) 앱 분리 — 앱 출시(AAB/APK)까지 남은 작업
배경·확정 사항은 `docs/recipe/CONTEXT.md` 18절이 단일 원본.
**목표는 "웹 기능을 옮기기"가 아니라 "기까를 별도 앱으로 출시하기"** 다 — 아래 순서가 그 기준으로 잡혀 있다.

### 완료 (2026-07-26)
- **백엔드**: `honeychest/gikka` 별도 저장소(패키지 `com.chs.gikka`). 서버에 떠 있다
  (`chs-gikka-1`, 8082). 자체 Jenkinsfile(Multibranch) · docker-compose · deploy-gikka.sh.
- **프론트**: lab 의 `frontend/src/domain/recipe/**`(55개) + `public/recipe/**` 를 gikka 저장소
  `frontend/` 로 옮기고 독립 빌드를 세웠다(테스트 65개·typecheck·build 통과, 323KB·cesium 0).
  전용 배포 스크립트(`frontend/deploy-front.sh`, 릴리스+심링크) · Jenkins 경로 감지(백엔드와
  프론트가 각각 필요할 때만 배포) · 롤백 도구(`rollback-gikka.sh`) 를 갖췄다.
- **앱 도메인 전환 완료**: `gikka.devcontext.net` 이 이제 gikka 저장소가 배포한 것만 본다 —
  정적은 `nginx/gikka/dist`, API 는 `chs_gikka`(8082). `grep -c cesium` 0 으로 확인.
  이전에는 lab 웹과 같은 dist 를 봤고, 그래서 lab 프론트 배포가 앱 내용물을 바꿨다.
  (lab 번들은 `index-*.js` 1.8MB + `Cesium.js` 5.7MB 를 앱 사용자에게도 내려보냈다.
  `vite-plugin-cesium` 이 index.html 에 태그로 주입하므로 lazy 화로는 안 걷힌다 — 이것이
  CONTEXT 18절의 "lazy 화로 충분"을 뒤집고 빌드를 가른 이유다.)

### 확정된 결정 (다시 논의하지 말 것)
1. **프론트는 gikka 저장소 안 `frontend/`** (별도 저장소로 또 가르지 않는다). 프론트와 백엔드는
   같은 앱이라 같이 배포되는 편이 정상이고, 저장소를 가르면 API 계약 변경이 두 커밋으로 쪼개진다.
2. **웹 `devcontext.net/recipe` 는 유지하지 않는다.** 앱 배포가 확인되면 폐지한다 →
   그래서 `devcontext.conf` 의 recipe 프록시를 `chs_gikka` 로 **전환하는 작업은 필요 없다**.
   대신 아래 "lab 청소" 에서 `location ^~ /api/recipe/llm/` 블록을 **지운다**.
3. **TWA 셸(Bubblewrap) 빌드 자리는 개발 PC 한 곳.** mac-mini 에는 설치하지 않는다.
   근거와 절차는 gikka 저장소 `android/README.md` 가 단일 원본.
4. **nginx 설정은 git 으로 배포되지 않는다.** 이 저장소의 `.gitignore` 가 `/chs/` 를 제외하므로
   `chs/server/nginx/*.conf` 는 로컬·서버 각자의 파일이다. 변경은 **서버에서 직접**
   (`/opt/homebrew/etc/nginx/servers/`) 하고, 이 체크아웃의 사본에도 같은 내용을 반영해 둔다 —
   사본이 낡으면 다음 사람이 서버 상태를 처음부터 다시 조사한다(이번 세션이 그 비용을 치렀다).

### 병행 기간 규칙 — 지금 사본이 둘이다
1. **recipe 프론트 수정은 gikka 저장소에서만.** lab 의 `frontend/src/domain/recipe/**` 는 곧 지울 사본이다.
2. **recipe 백엔드 수정도 gikka 저장소에서만.** `springboot/domain/recipe/**` 도 같다.
3. **gikka DB 마이그레이션(V16 이후) 추가 금지.** 두 앱이 같은 `gikka` DB 에 각자의 Flyway 로
   붙어 있어서, 새 파일을 gikka 저장소에만 넣으면 DB 이력에는 적용되고 이쪽 locations 에는 없다
   → **다음 백엔드 배포 때 springboot 앱이 "applied migration not resolved locally" 로 기동 실패**
   (validateOnMigrate 기본 true). 꼭 필요하면 같은 파일을 양쪽에 동시에 넣는다.

### 앱 패키징 1차 완료 (2026-07-27)
- URL 을 `/recipe` 에서 **루트로** 옮겼다(도메인이 앱 전용이라 경로에 이름을 또 붙일 이유가 없다).
  TWA 셸에 start_url 이 박히므로 스토어에 올리기 전에 해야 했던 작업이다.
- TWA 셸 첫 빌드 완료 — applicationId `gikka.app` · fallback `customtabs` · 알림 위임 on ·
  결제·위치 off · portrait. 설정 원본은 gikka 저장소 `android/twa-manifest.json`(커밋됨).
- `assetlinks.json` 을 `frontend/public/.well-known/` 에 두어 프론트 배포 파이프라인이 서빙한다.
- **APK 를 실기기에 설치해 주소창 없이 뜨는 것과 기능 전부를 확인했다.**

### 남은 것: Play Console (다음 작업)
1. 개발자 계정 등록(25달러 1회) → 비공개 테스트 트랙 → `app-release-bundle.aab` 업로드
2. **Play 앱 서명 키 SHA-256** 확보 → `assetlinks.json` 의 `sha256_cert_fingerprints` 배열에
   **추가**(로컬 업로드 키 지문과 나란히 둔다 — 그래야 내 APK 와 스토어 앱 양쪽 다 주소창이 없다)
   → push 하면 파이프라인이 배포한다
3. 릴리스마다 `twa-manifest.json` 커밋 — `appVersionCode` 가 거기 있고, 빠뜨리면 다음 빌드가
   옛 번호로 되돌아가 Play 가 업로드를 거부한다
4. 콘솔: Google OAuth 승인된 오리진에 `https://gikka.devcontext.net` 추가.
   동의 화면이 "테스트" 상태면 테스트 사용자 목록에 넣은 계정만 로그인된다(최대 100명)
5. 개인 계정은 프로덕션 승격에 "테스터 12명 · 14일 연속 설치 유지" 요건이 있다

절차·함정(윈도우 JDK 경로 공백 등)은 gikka 저장소 `android/README.md` 가 단일 원본.

### 그 뒤: lab 청소 (앱이 안정화된 뒤에만)
- `frontend/src/domain/recipe/**`(55개) · `frontend/public/recipe/**` 삭제
- recipe 참조 3곳 정리 — `app/router/MainRouter.jsx`(라우트·`GikkaHostGuard`·챗봇 숨김 접두어),
  `cssLayoutGuard.test.ts`(recipe 규칙 부분), `shared/ui/samples/visualSamples.js`(카탈로그 주석)
- `springboot/src/**/domain/recipe/**`(+test, 합 88개) 삭제, `application*.properties` 의 `gikka.*` 삭제,
  `RecipeIsolationArchTest` 삭제, recipe 전용 의존성 정리
  (`flyway-database-postgresql` 은 챗봇 pgvector 도 쓰는지 확인 후 판단)
- `devcontext.conf`(서버에서 직접 — 위 결정 4 참고) — `upstream chs_gikka` 는 `gikka.conf` 가
  쓰므로 **남기고**, `location ^~ /api/recipe/llm/` 블록만 삭제
- **권고: 옛 주소 리다이렉트를 남긴다.** `devcontext.net/recipe` 로 공유해둔 링크·북마크가 죽는다.
  ```nginx
  location ^~ /recipe { return 301 https://gikka.devcontext.net$request_uri; }
  ```
  단 **이미 설치된 iOS 홈화면 앱은 이걸로 살아나지 않는다** — PWA 는 오리진 단위로 저장소가
  갈려서 옛 오리진의 앱은 재설치 + 재로그인이 필요하다. 대상이 본인과 소수 테스터뿐이라
  한 번 안내하면 끝난다(안내를 빠뜨리면 "앱이 안 열린다" 로 돌아온다).
- **문서 봉인** (gikka 쪽 사슬은 2026-07-26 에 이미 끊었다 — gikka 문서 5개는 lab 을 참조하지 않는다):
  - `docs/recipe/{CONTEXT,PLAYBOOK,DECISIONS-LOG}.md` · `progress.html` 은 **여기 남긴다.**
    각 파일 맨 위에 한 줄 — "이 도메인은 `honeychest/gikka` 로 갔다. 이 파일은 2026-07-26
    까지의 이력 보관소이며 갱신하지 않는다." 옮기지 않는 이유: 새 저장소가 매 세션 끌고
    다닐 내용이 아니다. 1년에 한 번 "왜 이렇게 정했나"를 찾을 때만 열면 된다.
  - `AGENTS.md`(recipe 안내 3줄) · `frontend/AGENTS.md`(recipe 표·도메인별 절) ·
    `springboot/AGENTS.md`(recipe 패턴 표) 에서 recipe 서술 삭제
  - `frontend/.../backendPatterns.js` 의 `[gikka 저장소]` 표기 정리
  - 이 파일(HANDOFF.md)은 위 항목이 끝나면 **비운다**

## binance 자동매매 — LLM 시장 분석 다음 작업 (2026-09-02 이어서)
배경·설계는 `docs/binance/CONTEXT.md` 단일 원본. 오늘 커밋 4개로 멀티 타임프레임
(1m/5m/15m/4h) 라이브 버퍼 + 로컬 LLM(Mac-mini-LLM) 분석/질의응답 기능을 구현·수정
완료(`5d42b75`·`bebfdf0`·`e2e5543`·`8a32195`). 자동 5분 스케줄은 폐지하고 관리자가
"분석 요청" 버튼을 눌러야만 LLM을 호출하도록 전환됨(리소스 낭비 우려로 사용자 요청).

### 완료 — 분석 요청을 docker 인스턴스와 무관하게 (2026-09-04)
리더가 아닌 인스턴스(docker1/docker2)로 분석 요청이 가면 무조건 실패하던 문제(리더가 중간에
바뀌면 갑자기 안 되는 것도 같은 원인) 해결. 비리더가 Redis `server:leader` 값을 읽어 실제
리더로 1회 내부 전달하는 방식(`LeaderElectionService.getCurrentLeaderName()` + 신규
`BinanceAnalysisLeaderForwarder`)으로 구현·커밋(`e935d3d`)·푸시·배포 완료. 코덱스 검수(방향
논의 + 계획 xhigh 검수)로 인증 방식 오류(Authorization 아닌 Cookie)·ask 바디 재읽기 불가·
전달 실패 시 로컬 폴백의 LLM 중복호출 위험을 구현 전에 잡음. 배포 후 브라우저로 직접
확인 — 두 인스턴스 정상 기동, 기존 리더 경로(DOCKER1) 분석 요청 정상 동작(14.2초). 단,
비리더→리더 전달 경로 자체는 nginx `SRV_ID` 쿠키가 HttpOnly라 브라우저로는 재현 못 했고
단위테스트 10케이스(MockRestServiceServer)로만 검증됨 — 다음에 리더가 자연 전환될 때
실제 전달 경로를 한 번 더 확인하면 좋다.

### 다음 세션에서 할 일 (사용자가 요청, 다음으로 미룸)
1. **일봉(1d) 인터벌 추가** — 지금 1m/5m/15m/4h 4개뿐인데 4시간봉으로는 장기 추세 판단에
   부족하다는 지적. `BinanceKlineInterval`에 일봉을 추가하고 버퍼·툴·화면을 함께 확장한다.
2. **시간 표시를 KST로 변환** — 화면에 epoch ms(long)가 그대로 노출되는 곳이 남아있다는 지적.
   `formatTime` 헬퍼가 이미 적용된 곳과 안 된 곳을 구분해 확인 필요.
3. **결론 가독성 개선** — 시스템 프롬프트에 "5줄 이내 결론부터" 지침을 추가했으나, 사용자가
   화면에서 아직 개선을 체감 못함 — 재배포됐으니 다음에 재확인 필요.
4. **매수/매도 추천가격 표시** — `BinanceAnalysisChatClientConfig`의 현재 시스템 프롬프트는
   포지션 정보(방향·진입가·레버리지 등)가 없어 "정확한 손절가는 계산 못 한다"고 명시돼 있음
   (합의된 제약). 추천가격을 보여주려면 이 제약을 어떻게 풀지(포지션 정보를 입력받을지,
   계속 "기술적 후보"로만 표시할지) 사용자와 먼저 다시 확인해야 한다.

### 아직 안 한 것
- `chs/server/nginx/devcontext.conf`에 `/api/admin/test/binance/debug/analysis` 전용
  location 블록(60초 타임아웃)을 로컬 사본에 추가했으나 **실서버 반영은 아직 확인 안 됨**
  (nginx 설정은 git 배포 대상이 아니라 서버에서 직접 확인해야 함).

## binance signal — energy 히스토리 시작시각 + kline temp 정식화 (2026-09-03)

### 완료 — energy 히스토리 시작시각 지정
signal 페이지 long/short energy·청산 합계에 시작 시각을 직접 지정하는 기능. 계획→Codex
검수→구현까지 끝났고 이 세션에서 커밋됨(`SignalController`/`SignalDataService`/`SignalPage`
/`TopBar` + signal 전용 `datetimeLocal` 모듈). 캔들·OI 차트는 기존 프리셋 그대로 유지.

### 계획만 완료 — kline temp 표 정식화 (다음 세션에서 이어감)
`agg_trade_1m_temp`가 설계상 "임시"인데 2026-08-31 raw tick 중단 이후 사실상 유일한
실시간 kline 원천이 됨(전체 행을 자바로 읽는 구조라 512MB 힙 제약에서 위험). 5분 네이티브
저장 + 롤업 대신 리필 방식으로 재설계하는 계획을 `docs/binance/kline-temp-retire-plan.md`에
정리함(GitNexus로 확인: 지켜야 할 경계는 `SignalCandleSource` 인터페이스뿐, 그 아래는
자유롭게 재작성 가능). **다음 세션 할 일**: 표 이름 확정(가안 `binance_kline_5m` 권장,
계획 문서 참고) → 구현 착수.
**죽음조건: 구현·배포가 끝나면 이 절과 `docs/binance/kline-temp-retire-plan.md`를 지운다.**
