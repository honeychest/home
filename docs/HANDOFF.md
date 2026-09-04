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

### 계획 v3 (조건부 가능, 1~6단계+7단계 부분 완료) — kline temp 표 정식화 (다음 세션에서 이어감, 2026-09-04)
`agg_trade_1m_temp`가 설계상 "임시"인데 2026-08-31 raw tick 중단 이후 사실상 유일한
실시간 kline 원천이 됨(전체 행을 자바로 읽는 구조라 512MB 힙 제약에서 위험). 표 이름
`binance_kline_5m` 확정. Codex xhigh 적대적 검수(2026-09-04) 결과 v2는 처음엔 **보류** —
`SignalCandleSource` 인터페이스만 지키면 된다는 v2의 GitNexus 기반 전제가 불완전했다(이
인터페이스를 우회해 legacy 표를 직접 읽는 `PatternMatchService`·`AnalysisSearchService`를
놓쳤고, `DataIntegrityEvaluator`(60분)·`AnalysisDetectionScheduler`(1440분)가 요구하는
1분 범위, 진행봉(IN_PROGRESS) 경로도 계획에 없었다). 재작성한 v3(`docs/binance/
kline-temp-retire-plan.md`)에 9단계 실행 순서를 정리하고, 사용자 결정 필요 3가지
(PatternMatch·AnalysisSearch 포함 / SPOT+FUTURES 유지 / `KLINE_5M` 표기 변경)를 모두
확정(가/가/가) — 1단계(계약·범위 확정) 완료, **조건부 가능**으로 전환됨.
2단계(기준선 실측)도 완료 — `springboot/.env` 로컬 자격으로 공유 DB 직접 조회(읽기 전용).
실측 대상은 `BTCUSDT`·`ENAUSDT` × `SPOT`·`FUTURES` 4개 조합뿐이며, legacy
`agg_trade_1m`/`agg_trade_5m`가 cutover 이후 약 3.44일째 정지 상태임을 확인해 치명 2·3
(PatternMatch·AnalysisSearch 고립)이 실측으로 재확인됨. 15분 AnalysisTemplate delta는
자바 집계 대신 SQL GROUP BY 전환을 권장하는 결론으로 "미확정 사항" 해소.
3단계(interval 공통 계층)도 완료 — `BinanceKlineRestClient`·`BinanceKlineRangeFetcher`·
`BinanceKlineWindow`에 `BinanceKlineInterval`(기존 enum, `FIVE_MINUTES` 이미 있음)을 받는
오버로드를 additive로 추가, 기존 1분 경로는 시그니처·동작 변경 없음. 테스트 4개 클래스
(계획엔 3종만 적혀 있었으나 `BinanceKlineTempSyncServiceTest`도 mock stub 갱신 필요해서
같이 처리) 29개 전부 통과, compile 통과.
4단계(마이그레이션+repository)도 완료 — `V11__add_binance_kline_5m.sql` + `BinanceKline5m`
entity + `BinanceKline5mRepository` 신설, 순수 additive라 기존 코드 영향 없음. **아직 실제
DB엔 적용 안 됨**(로컬·prod가 같은 DB를 봐서 다음 재기동 때 Flyway가 자동 적용 — 배포·재기동
시점은 사용자가 정함).
5단계(writer·sync)도 완료 — `BinanceKline5mWriter`+`BinanceKline5mSyncService` 신설.
"tail vs gap 별도 경로"가 아니라 "매 회차 최근 48시간 전체를 리필"하는 단일 경로로 구현
(RefillResult 5개 지표, gap 병합, 회차당 20-range 상한, range마다 리더 재확인, 429/5xx
재시도, manualRefill 팔로워 차단). 커밋 전 Codex commit-check(xhigh)로 결함 2건 추가 수정
(상태 조회 실패 격리, 쓰기 직전 리더 재확인). 테스트 11개 신규 통과, binance 도메인 전체
207개 회귀 없음. **스케줄러는 아직 실질적으로 아무 일도 안 함**(binance_kline_5m 표가 실제
DB에 없어서 — 4단계 참고, 배포 전까지는 무해).
6단계(1분·진행봉 경로 보존)도 완료 — 검증만 하고 **새 코드는 안 씀**: `DataIntegrityEvaluator`
·`AnalysisDetectionScheduler`가 `Interval.ONE_MINUTE`만 쓰는 걸 재확인(이번 v3가 ONE_MINUTE
읽기 경로를 애초에 안 건드려서 구조적으로 무영향, 기존 테스트 7개 그대로 통과), 진행봉은
7단계에서 IN_PROGRESS를 canonical로 안 옮기고 지금 1분-temp 경로에 남기기로 확정. "50분
이하만 REST" 옛 설계는 폐기(1분 temp 전량 유지로 대체).
**이 세션에서 실제로 배포됨(6865d5b, f48e080)** — 배포 후 DB 직접 조회로 확인: V11
마이그레이션 성공 적용, `binance_kline_5m`에 4개 조합 각 576개(48시간) 정상 채워짐(shadow
read parity 0건 불일치), 기존 1분 temp 회귀 없음.

**배포 중 별도로 발견·해결한 것(kline_5m과 무관, 기존 설계 문제)**: 배포 직후
`AnalysisDetectionScheduler`가 BTCUSDT·ENAUSDT "1440개 결측/불연속" WARN을 계속 찍는
현상 발견 → 맥미니 원격 세션 + Codex(진단 2라운드, effort=high)로 근본 원인 규명:
1분 temp 수집기의 설계상 신선도 지연(3~5분, 안전지연 2분+tick 위상)과
`AnalysisDetectionScheduler`의 "지금 이 순간 기준 정확히 1440개" 요구가 애초에 계약
불일치였던 것 — 내부 결측은 없음, 오탐. kline_5m 작업과 무관하다고 Codex가 결론(리더
강제전환·1분 경로 추가조사 모두 근거 없음이라 기각). **후속 과제로 별도 분리**: 이
탐지기의 시간창 정의를 손봐야 함(신선도 지연을 계약으로 인정하거나 창 정의를 바꾸거나) —
이번 계획 문서 범위 밖.

7단계(source 읽기 전환)도 **부분 완료** — 착수 전 Codex xhigh 계획 검수로
`binance_kline_5m`이 최근 48시간 롤링 윈도우만 유지해 cutover~48시간전 사이 487개
캔들이 비어있는 걸 발견, 백필부터 하기로 결정(하이브리드 폴백은 기각).
- `BinanceKline5mSyncService.manualBackfillRange()` 신설(기존 리필 로직 `refillRange()`로
  공통 추출·재사용) + `ManualBackfillService`에 `KLINE_5M` 타입 추가(기존 admin API 재사용,
  새 컨트롤러 없음). **아직 실행 안 함** — 배포 후 4번 호출 필요(계획 문서 7단계 절 참고).
- `BinanceKlineSignalCandleSource`의 5분 COMPLETED 읽기를 canonical로 전환 완료
  (IN_PROGRESS·1분은 기존 temp 경로 그대로). `AnalysisTemplateService.getDelta()`에
  5분/15분 90일 상한 추가(무제한 조회 시 512MB 힙 위험 완화 — Codex 지적, SQL GROUP BY
  전환은 이번엔 보류).
- **PatternMatchService·AnalysisSearchService 전환은 이번 범위에서 제외** — Codex 검수
  결과 단순 교체가 아니라 별도 read model 설계가 필요할 만큼 커서(AggTrade5m 억지 변환
  금지, LAG 경계·1분 검색 대상 등 결정 필요) 다음 세션으로 미룸. 두 서비스는 지금처럼
  legacy 표를 계속 읽음(회귀 없음).
- 커밋 전 Codex commit-check(xhigh)로 결함 3건 추가 수정(in-flight 충돌 시 거짓 성공,
  manualBackfillRange 자체 경계 검증 부족, 90일 상한 계산 오버플로).
- springboot 전체 테스트 스위트(434개) 통과.

**다음 세션 할 일**:
1. 이번 커밋(아직 미완료 — 아래 "지금 커밋 대상" 참고) 배포 후 백필 4건 실행
   (`POST /api/admin/backfill/collect`, type=KLINE_5M, 계획 문서 7단계 절의 정확한
   fromMs/toMs 참고) → `/api/analysis/delta`·`/ws/candle/5m`·`/ws/candle/15m` 확인.
2. `AnalysisDetectionScheduler` 시간창 계약 문제 별도 해결(이번 계획과 분리된 과제).
3. `PatternMatchService`·`AnalysisSearchService`의 canonical/temp 전환 계획을 새로 짠다
   (`codex-review-step7-plan.md` 3절 — 세션 스크래치패드, 다음 세션엔 없을 수 있으니
   필요하면 핵심만 이 문서에 옮겨 적을 것).
4. 8~9단계(gap 관리자 갱신, dual-write/rollback 검증 후 cutover) 착수.
**죽음조건: 구현·배포가 끝나면 이 절과 `docs/binance/kline-temp-retire-plan.md`를 지운다.**
