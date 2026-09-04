# HANDOFF — 일회성 작업 인수인계 (완료되면 이 파일을 비운다)

> 용도: 상설 현황판(progress.html)이 없는 일회성 작업의 세션 간 인계.
> 새 세션(Claude·Codex 무관)은 이 파일이 비어 있지 않으면 먼저 읽고 이어서 작업한다.
> **끝난 항목은 남기지 않고 지운다** (경위는 git 이력에 있다).

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

### 계획 v3 (1~7단계 구현 완료, 운영 배포 확인 필요) — kline temp 표 정식화 (다음 세션에서 이어감, 2026-09-04)
`agg_trade_1m_temp`가 설계상 "임시"인데 2026-08-31 raw tick 중단 이후 사실상 유일한
실시간 kline 원천이 됨(전체 행을 자바로 읽는 구조라 512MB 힙 제약에서 위험). 표 이름
`binance_kline_5m` 확정. Codex xhigh 적대적 검수(2026-09-04) 결과 v2는 처음엔 **보류** —
`SignalCandleSource` 인터페이스만 지키면 된다는 v2의 GitNexus 기반 전제가 불완전했다(이
인터페이스를 우회해 legacy 표를 직접 읽는 `PatternMatchService`·`AnalysisSearchService`를
놓쳤고, `DataIntegrityEvaluator`(60분)·`AnalysisDetectionScheduler`(1440분)가 요구하는
1분 범위, 진행봉(IN_PROGRESS) 경로도 계획에 없었다). 재작성한 v3(`docs/binance/
kline-temp-retire-plan.md`)에 9단계 실행 순서를 정리하고, 사용자 결정 필요 3가지
(PatternMatch·AnalysisSearch 포함 / SPOT+FUTURES 유지 / `KLINE_5M` 표기 변경)를 모두
확정(가/가/가) — 1~7단계 구현과 로컬 검증을 완료했으며, **운영 배포 확인이 남아 있다**.
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
entity + `BinanceKline5mRepository` 신설, 순수 additive라 기존 코드 영향 없음. (이후 배포로
실제 DB에도 적용 완료 — 아래 6단계 문단 참고.)
5단계(writer·sync)도 완료 — `BinanceKline5mWriter`+`BinanceKline5mSyncService` 신설.
"tail vs gap 별도 경로"가 아니라 "매 회차 최근 48시간 전체를 리필"하는 단일 경로로 구현
(RefillResult 5개 지표, gap 병합, 회차당 20-range 상한, range마다 리더 재확인, 429/5xx
재시도, manualRefill 팔로워 차단). 커밋 전 Codex commit-check(xhigh)로 결함 2건 추가 수정
(상태 조회 실패 격리, 쓰기 직전 리더 재확인). 테스트 11개 신규 통과, binance 도메인 전체
207개 회귀 없음. (배포 후 스케줄러가 실제로 동작한 것은 아래 6단계 문단에서 확인.)
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
  새 컨트롤러 없음). **백필 실행 완료(2026-09-04, c102bb7 배포 뒤 맥미니 원격 세션이
  리더 인스턴스에서 4건 실행)** — DB 재확인: 4개 조합 전부 487/487, 표 전체가
  2026-08-31 12:50부터 지금까지 완전 연속. 실행 중 관리자 화면에 `KLINE_5M` 옵션이 없어
  첫 시도가 실수로 `KLINE_1M`(1분 temp)에 153건 들어갔으나 이미 채워진 구간이라 무해
  확인(INSERT IGNORE) — `ManualCollectCard.jsx`에 옵션 추가는 8단계로 후속 과제화.
- `BinanceKlineSignalCandleSource`의 5분 COMPLETED 읽기를 canonical로 전환 완료
  (IN_PROGRESS·1분은 기존 temp 경로 그대로). `AnalysisTemplateService.getDelta()`에
  5분/15분 90일 상한 추가(무제한 조회 시 512MB 힙 위험 완화 — Codex 지적, SQL GROUP BY
  전환은 이번엔 보류).
- **PatternMatchService·AnalysisSearchService 전환 구현 완료, 배포 후 확인 필요** — 두 서비스 모두
  `SignalCandleSource`를 사용해 cutover 이전 legacy 표와 이후 canonical/temp 표를 같은
  계약으로 읽는다. 장기 분석 검색은 7일 단위로 나눠 읽어 힙 적재를 제한하고, 전환 후
  호출되지 않는 legacy 유사 검색 repository 메서드는 삭제했다.
- 분석 화면의 심볼은 `BTCUSDT`·`ENAUSDT` 전체 형식으로 통일했고, API 경계에서는 짧은
  심볼도 한 번 정규화한다. 검색의 거래량 조건은 차트와 같은 base volume 단위를 사용한다.
- 커밋 전 Codex commit-check(xhigh)로 결함 3건 추가 수정(in-flight 충돌 시 거짓 성공,
  manualBackfillRange 자체 경계 검증 부족, 90일 상한 계산 오버플로).
- springboot 전체 테스트 스위트(439개) 통과.

**이 세션에서 추가로 확인됨**: `/api/signal/candles`(5m·15m) 72시간 범위 실운영 조회 —
5분봉 861개·15분봉 286개 전부 간격 이상·null/0-캔들 0건, 백필 경계 포함 완전 연속. 7단계
(부분) 작업은 이걸로 실제 검증까지 끝남.

**다음 세션 할 일**:
1. 새 심볼 계약·공통 원천 전환을 배포한 뒤 `/api/analysis/search`와 관련 화면을 실운영에서 확인.
2. `AnalysisDetectionScheduler` 시간창 계약 문제 별도 해결.
3. 8~9단계(gap 관리자 갱신, dual-write/rollback 검증 후 cutover) 착수.
**죽음조건: 구현·배포가 끝나면 이 절과 `docs/binance/kline-temp-retire-plan.md`를 지운다.**
