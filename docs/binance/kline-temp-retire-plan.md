> **죽음조건**: 이 계획을 실제로 구현·배포하면 이 문서를 지운다(경위는 git 이력에 남는다).
> 진행 상황·후속 요약은 `docs/binance/CONTEXT.md` "6. 아직 안 끝난 것"과 `docs/HANDOFF.md`를 본다.

# agg_trade_1m_temp 정식화 계획 v3 — Codex 적대적 검수 반영, 실행 순서 재작성

작성: Claude (v2에 대한 Codex xhigh 적대적 검수 결과를 반영해 재작성)
대상 저장소: C:\Users\Tissue\IdeaProjects\lab
범위: kline 원천 전환 계획과 진행 기록. signal-history 기능(완료됨)과는 무관.

## v3에서 바뀐 것 (v2 대비)

v2는 "표 이름 확정 → 마이그레이션 → 백필 → 전환"이라는 좁은 실행 순서를 제시했다. Codex
적대적 검수(2026-09-04, xhigh, 14분)가 v2의 전제 두 가지를 무너뜨렸다:

1. **"15분·4시간 재집계는 항상 소량이라 자바로 충분하다"는 결론은 절반만 맞다.**
   시그널 스트림 경로(`CandleStreamService`)는 실제로 소량(5분봉 3~6개)만 다루지만,
   `AnalysisTemplateService.getDelta()`(`springboot/.../analysis/service/
   AnalysisTemplateService.java:66-81`)는 상한 없는 임의 range를 그대로
   `SignalCandleSource.find()`에 전달하고, 프론트 분석 화면(`frontend/src/page/analysis/
   hooks/useBinanceKlines.js:80-108`)은 날짜 전체 단위로 15분봉을 요청한다 — 5일 조회면
   5분봉 약 1,440개를 자바가 묶어야 한다. **4시간은 애초에 `SignalCandleSource`에 없다**
   (아래 "정정" 참고) — v2가 "15분·4시간 인터벌"이라고 반복해서 쓴 것 자체가 오류다.
2. **v2가 "예상 변경 파일"에 넣지 않은 소비자 4곳이 canonical 전환 시 조용히 깨진다.**
   `PatternMatchService`(최근 신호), `AnalysisSearchService`(분석 검색), `DataIntegrityEvaluator`
   (무결성 감시), `AnalysisDetectionScheduler`(1분 템플릿 탐지), 그리고 `CandleStreamService`의
   진행봉(IN_PROGRESS) 경로. 아래 "전제 정정"과 "치명 결함" 절이 근거다.

v3는 Codex가 제시한 9단계 실행 순서로 전면 재작성했다. 사용자가 범위를 확정한 뒤
1~7단계와 `PatternMatchService`·`AnalysisSearchService`의 공통 원천 전환까지 구현·검증했다.
현재 남은 것은 관리자 gap 운영과 롤백 기간 검증이다.

## 정정 — SignalCandleSource에는 4시간 인터벌이 없다

`springboot/.../service/SignalCandleSource.java:12-16`의 `Interval` enum은
`ONE_MINUTE`·`FIVE_MINUTES`·`FIFTEEN_MINUTES`만 선언한다. 4시간이 있는 것은 별도의
`BinanceKlineInterval`(`springboot/.../model/BinanceKlineInterval.java:6-10`)이며, 이는
`LiveMarketDataService`·LLM 시장분석 버퍼(`BinanceAnalysisTools.java`) 전용 — **이 계획의
대상(signal의 5분 canonical 전환)과 무관하다.** v2 본문의 "15분·4시간" 서술은 전부 "15분"
으로 정정하고, 4시간 관련 서술은 삭제한다.

검수 당시 `PatternMatchService`는 `AggTrade5mRepository`로 **legacy `agg_trade_5m` 표를
직접** 읽었고 market_type 조건도 없었다. 현재는 `SignalCandleSource`가 제공하는 선물 가격과
현물·선물 delta 합성 계약을 사용한다. `FOUR_HOURS_MS`는 블록 시작 계산과 중복 후보 제거에만
쓰인다.

## 검수 당시 기준과 현재 상태

- `agg_trade_1m_temp` 원래 계약: "임시 검증용, 레거시 롤업과 연결 안 함"
  (`V10__add_agg_trade_1m_temp.sql:1-2`).
- 2026-08-31 커밋 `33bc1e4`가 raw_agg_trade/레거시 롤업 쓰기를 껐고, 다음날 커밋 `831b94d`가
  `BinanceKlineSignalCandleSource`를 만들어 cutover 이전은 레거시(`agg_trade_1m`/`5m`),
  cutover 이후는 temp를 읽는 혼합 경로로 응급 복구했다. cutover 값
  `binance.agg-trade.legacy-cutover-ms=1788180800000` = 2026-08-31 12:53:20 UTC.
- **레거시 5분 롤업(`AggTradeRollupService`)은 `binance.agg-trade.save.enabled=false`라
  cutover 이후 더 이상 쓰지 않는다**(`AggTradeRollupService.java:68-69, 412-449`,
  `application.properties:134-139`). 현재 `PatternMatchService`와 `AnalysisSearchService`는
  `SignalCandleSource`를 통해 cutover 이전 legacy와 이후 canonical/temp를 함께 읽는다.
  legacy 표는 pre-cutover 과거 데이터 원본으로 계속 보관한다.
- temp 조회는 `BinanceKlineTempCandleRepository`가 페이지네이션·DB 집계 없이 전체 List를
  반환하고, `BinanceKlineFiveMinuteAggregator`가 자바에서 5분으로 합친다. 512MB 힙·2인스턴스
  제약(`springboot/AGENTS.md`)에서 조회 범위가 길어질수록 위험.
- 정기 쓰기: `BinanceKlineTempSyncService.syncScheduled()`(leader 전용, 60초 주기)가
  "마지막 저장 캔들 이후 ~ 지금"만 앞으로 채운다. 최대 48시간까지만 자동 caught-up.
- 별도 관리자 수동 도구: `BinanceKlineGapService` + `DataGapAdminService` — 지정 범위에서
  DB와 Binance REST의 시각 집합을 비교해 GAP을 "탐지만" 한다. 자동 복구 없음. **days 파라미터가
  1·2일로만 허용되고 명시 범위도 최대 48시간으로 제한된다**(`BinanceKlineGapService.java:99-114`,
  `BinanceKlineRangeFetcher.java:104-109`) — "48시간 밖 이력도 점검 가능"이라는 v2의 전제는
  현재 도구로는 불가능하다(아래 "높음 7" 참고).

## 표 이름 — 확정

**`binance_kline_5m`**(사용자 협의 완료, 2026-09-04). 재협의 단계는 실행 순서에서 제거한다.
entity·repository·관리자 화면 표기(`KLINE_5M`)까지 고정했다.

## GitNexus 영향 분석에 대한 안내

이번 정리에서는 GitNexus 색인을 현재 커밋에 맞춰 재생성한 뒤, 변경 대상 심볼별 upstream
영향 분석을 실행했다. `SignalCandleSource`와 두 repository 인터페이스는 여러 서비스에서
사용하므로 변경 전 직접 호출자와 테스트를 함께 확인한다.

### 실제 소비자 목록

| 대상 | 방식 | 비고 |
|---|---|---|
| `CandleStreamService` | `SignalCandleSource.find()` (COMPLETED·IN_PROGRESS 모두) | 진행봉 포함 — v2 누락 |
| `SignalDataService` | `SignalCandleSource.find/findBefore/sumEnergy` | v2 표에서 누락 |
| `DataIntegrityEvaluator` | `SignalCandleSource.find()` interval=1분, 최근 60분 | v2에 있었으나 "1분 60분 요구"라는 실제 범위가 명시 안 됨 |
| `AnalysisDetectionScheduler` | `SignalCandleSource.find()` interval=1분, 최근 1440개 연속성 검사 | 상동 |
| `AnalysisTemplateService.getDelta` | `SignalCandleSource.find()` interval=15분, **상한 없는 임의 range** | v2가 2단계로만 표시, 범위 무제한이라는 사실 누락 |
| `SignalCandleAnalysisConverter` | 변환만 | v2와 동일 |
| `PatternMatchService` | `SignalCandleSource.find()`(legacy/canonical 경계 포함) | 공통 원천 전환 완료 |
| `AnalysisSearchService` | `SignalCandleSource.find()`(1m/5m/15m, 7일 chunk) | 공통 원천 전환 완료 |

**결론**: `SignalCandleSource`가 legacy/canonical 경계를 소유하고, 모든 시그널·분석 소비자가
그 계약을 사용해야 한다. 과거 원본인 legacy 표는 삭제하지 않고 source 내부에서만 읽는다.

## 목표 구조 (정정)

~~~
Binance REST/WS kline (interval=5m, SPOT+FUTURES 유지 — 확정, "결정 완료" 참고)
  -> canonical 5분 kline 표 binance_kline_5m (신규, 완료봉만 저장)
  -> SignalCandleSource 구현체 (1m/5m/15m 계약 불변)
     - 1분(ONE_MINUTE)은 손대지 않는다 — agg_trade_1m_temp 전량 저장·읽기를 그대로 유지
       (치명 1 해결책, 아래 참고). "50분 이하만 REST 직접조회" 설계는 폐기.
     - 5분 COMPLETED는 canonical 읽기로 전환(7단계)
      - 15분은 source 내부의 자바 `aggregate()`로 canonical 5분 리스트를 집계한다.
        `AnalysisTemplateService.getDelta()`에는 90일 상한을 둔다
     - IN_PROGRESS(5분·15분)는 canonical이 아니라 기존 temp 1분 기반 부분 집계 경로를
       그대로 쓴다(치명 4 해결책, 아래 참고) — canonical은 완료봉만 저장해 애초에
       진행봉을 만들 수 없다
  -> PatternMatchService·AnalysisSearchService — SignalCandleSource 공통 원천 사용 완료
~~~

## 치명 결함 (Codex 적대적 검수, 구현 착수 전 반드시 해소)

### 치명 1. 1분 공개 계약을 끊지만 대체 경로가 없다 — [해결, 2026-09-04, 6단계]

계획(구 버전)은 1분 저장을 없애고 50분 이하에서만 REST 직접 조회한다고 했다. 그러나:

- `DataIntegrityEvaluator`는 최근 **60분**을 `Interval.ONE_MINUTE`로 조회해 존재 행 수를
  60과 비교한다(`DataIntegrityEvaluator.java:19-25, 58-68`).
- `AnalysisDetectionScheduler`는 최근 **1,440개**의 1분봉을 조회해 연속성을 검사한다
  (`AnalysisDetectionScheduler.java:29-36, 42-68, 92-105`).

**해결책(REST 직접조회도 별도 버퍼도 아니다)**: "50분 이하만 REST" 설계 자체를 폐기하고
`agg_trade_1m_temp` 1분 전량 저장·읽기를 **그대로 유지**한다 — 이번 v3 마이그레이션은
5분(canonical)만 신설할 뿐 1분 경로는 애초에 건드리지 않는다. 두 클래스 모두
`Interval.ONE_MINUTE`만 쓰고(`FIVE_MINUTES`를 쓰지 않음 — 위 코드 재확인함) `SignalCandleSource`
의 ONE_MINUTE 읽기 경로(`combineTempOneMinute`)는 7단계에서도 변경 대상이 아니므로 구조적으로
영향이 없다. 기존 테스트(`DataIntegrityEvaluatorTest`·`AnalysisDetectionSchedulerTest`) 7개
그대로 통과 확인(회귀 없음, 애초에 코드 변경도 없음).

### 치명 2. PatternMatch(최근 신호)가 legacy 표에 고립된다 — [해결, 2026-09-04]

`PatternMatchService`는 `/api/signal/pattern`·`/api/signal/score`에서 호출되며, 이제
`SignalCandleSource`를 통해 legacy/canonical 경계를 사용한다. 선물 가격과 현물·선물 delta
합성 계약도 다른 시그널 소비자와 같아졌다.

### 치명 3. 분석 검색도 legacy 표를 계속 읽는다 — [해결, 2026-09-04]

`AnalysisSearchService`는 1m/5m/15m 모두 `SignalCandleSource.find()`를 사용한다. 7일
chunk 단위로 읽고 이전 봉을 다음 구간으로 넘겨 가격 변화율 조건을 유지한다. 프론트
`/api/analysis/search`의 심볼과 캔들 원천도 다른 분석 경로와 같은 계약으로 통일했다.

### 치명 4. 진행봉(실시간 캔들) 스트림이 사라진다 — [해결, 2026-09-04, 6단계]

`CandleStreamService`는 진행봉을 `QueryMode.IN_PROGRESS`로 요청한다
(`CandleStreamService.java:106-145`). 현재 source는 1분 temp를 읽어 5분으로 **부분** 집계해
반환한다(`BinanceKlineSignalCandleSource.java:258-300`). canonical 표가 완료봉만 저장하고
1분 temp 쓰기를 끄면, 현재 진행 중인 5분/15분봉을 만들 입력이 없어진다 — `/ws/candle/5m`·
`/ws/candle/15m`의 실시간 업데이트가 멈춘다.

**해결책**: 치명 1과 같은 뿌리 — 1분 temp 쓰기를 끄지 않으므로 진행봉을 만들 입력 자체가
사라지지 않는다. 7단계에서 `BinanceKlineSignalCandleSource`를 canonical 읽기로 바꿀 때,
**COMPLETED 5분만 canonical로 라우팅하고 IN_PROGRESS 5분·15분은 지금의 1분-temp 기반
부분집계 경로(`findTempFive`/`combineTempOneMinute`)를 그대로 남겨둔다** — 새 코드
불필요, `QueryMode` 분기 하나만 유지하면 된다. canonical은 애초에(리필 창이 `safeEnd`
까지만 조회하도록 설계돼) 진행 중인 봉을 저장하지 않으므로(4·5단계에서 이미 그렇게
구현함) 이 라우팅이 유일하게 가능한 경로다.

## 높음 — 실행 전 반드시 반영

- **REST/fetcher/window의 1분 하드코딩(3곳)이 예상 변경 파일에서 빠짐**: `BinanceKlineRestClient`
  (INTERVAL="1m" 고정, `:18-20, 123-134`), `BinanceKlineRangeFetcher`(INTERVAL_MS=60_000L,
  `:15-20, 47-89, 95-109`), `BinanceKlineWindow`(INTERVAL_MS=60_000L + 2분 안전지연,
  `:7-35`). 특히 `safeEnd()`를 그대로 5분에 적용하면 5분 경계가 아닌 값이 나와
  `validateRange()`가 즉시 실패한다 — interval을 받는 구조로 먼저 바꿔야 한다.
- **리더·동시성 가드가 리필 안전성을 보장 못함**: `BinanceKlineTempSyncService.rangeSync()`에는
  leader 검사가 없고(`:141-159`), `inFlightRanges`는 JVM 로컬 집합이라 인스턴스 간 중복 실행을
  막지 못한다(`:31, 137-157`). 관리자 수동 백필(`ManualBackfillService.java:125-142`)도
  follower 여부 확인 없이 `rangeSync()`를 호출한다. range마다 leader 재확인 + 분산 lease 또는
  리더 전달(이번 세션 앞서 완료한 `BinanceAnalysisLeaderForwarder` 패턴 재사용 검토) 필요.
- **"48시간 밖 갭도 점검 가능"이 실제 도구와 모순** — `BinanceKlineGapService`/
  `BinanceKlineRangeFetcher` 모두 최대 48시간 상한이 있다(`:99-114`, `:104-109`). 유지하려면
  관리자 요청을 48시간 chunk로 분할·합산하는 로직을 추가하거나, 이 보장을 계획에서 뺀다.
- **dual-write·rollback이 "포함 가능" 수준으로만 적혀 있어 실제 롤백이 안 됨** — 구버전
  source는 1분 temp를, 신버전은 canonical 5분을 읽는다. 1분 temp 쓰기를 끄고 롤백하면
  구버전이 최근 구간을 못 읽는다. migration 선배포 → canonical backfill → shadow 비교 →
  dual-write → source read 전환(feature flag) → 롤백 대상 확인까지 순서를 못박아야 한다.
- **빈 응답 vs 실패 구분이 tail sync에만 있고 리필 완결성 검증은 부족** — `writer`가
  `INSERT IGNORE`로 양수 결과만 합산하는데(`BinanceKlineTempWriter.java:14-21, 29-58`),
  일부 행 무시도 "삽입 0"처럼 보일 수 있다. `expected`(있어야 할 시각 수)·`fetched`(REST
  응답 수)·`inserted`·`present_after`(쓰기 후 DB 실count)·`remaining_gap`을 리필마다 기록.
- **REST 호출 예산·재시도 정책이 없음** — 48시간 안에 5분 단위로 듬성듬성 gap이 있으면 호출이
  급증할 수 있다. 인접 gap 병합, 회차별 호출 상한, 429/5xx 재시도 backoff 필요.

## 중간 — 결정 완료 (사용자 확정, 2026-09-04)

- **PatternMatch·AnalysisSearch를 이번 canonical 전환 범위에 포함한다.** 예상 변경 파일이
  최소 4개 더 늘어난다(`PatternMatchService`, `AnalysisSearchService`, 관련 repository,
  컨트롤러) — 실행 순서 7단계에서 함께 전환.
- **SPOT+FUTURES 결합 저장을 유지한다.** 현재 source와 동일하게 가격은 FUTURES, delta는
  SPOT+FUTURES 합산(`BinanceKlineSignalCandleSource.java:227-297`)을 canonical 표에서도
  그대로 둔다 — 기존 화면 계약 변경 없음. `docs/binance/CONTEXT.md:11-20, 53-57`의 "선물
  전용, spot 사용 금지" 불변 규칙은 이 사실(delta 계산에는 spot 시세를 함께 쓴다)을 반영해
  문구를 갱신해야 한다 — canonical 전환과 별개로 처리할 문서 정정.
- **관리자 표기를 `KLINE_1M`에서 `KLINE_5M`으로 바꾼다.** `DataGapAdminService`·
  `DataGapCard.jsx`·`ManualCollectCard.jsx`를 실행 순서 8단계에서 함께 갱신, alias는
  두지 않는다.

## 짧은 구간(≤50분) 1분 조회 처리안 — [폐기, 2026-09-04, 6단계]

v1·v2가 제안했던 "50분 이하만 REST 직접조회, 그 이상은 저장 안 함" 설계는 폐기한다.
치명 1 해결책과 같은 이유 — 1분 temp 저장을 전량 유지하기로 하면서 이 표 자체가
무의미해졌다(≤50분이든 60분이든 1440분이든 전부 기존 temp 읽기 경로로 충분). 아래
표는 v2 당시의 검토 흔적으로만 남긴다.

| 방식 | 장점 | 단점 |
|---|---|---|
| 요청마다 Binance REST 직접 조회 | 저장·정합성 관리 불필요, 코드 최소 | 매 요청마다 외부 API 호출 1회 |
| 소규모 인메모리 버퍼 | 응답 빠름 | 2인스턴스 각각 별도 상태, 재시작 시 초기화 |

## 실행 순서 (v3 — Codex 제안 9단계로 전면 재작성)

1. **[완료, 2026-09-04] 계약과 범위를 확정한다.**
   `SignalCandleSource`의 1m/5m/15m 반환 의미와 COMPLETED/IN_PROGRESS는 계약으로 고정.
   PatternMatch·AnalysisSearch 포함, SPOT+FUTURES 유지, `KLINE_5M` 표기 변경 — 모두 확정
   (위 "결정 완료"). 4h는 인터페이스에 추가하지 않고 LiveMarketData·PatternMatch의 별도
   경로로 문서화한다(위 "정정" 절 참고).

2. **[완료, 2026-09-04] 전환 전 기준선을 실제 범위로 측정한다.**
   결과는 아래 "기준선 측정 결과" 절 참고. 1분 요청 60분·1440분·50분 이하, legacy 표
   최신 시각, symbol·market_type별 시각 집합·합계를 실측·기록 완료.

3. **[완료, 2026-09-04] interval 공통 계층(REST/fetcher/window)을 먼저 바꾼다.**
   `BinanceKlineRestClient.fetchPage`·`BinanceKlineRangeFetcher`(생성자+`fetch`+`validateRange`
   +`validateBoundedRange`)·`BinanceKlineWindow`(`fromLastCandle`+`safeEnd`+`nextPageStart`)
   전부 기존 `BinanceKlineInterval` enum(이미 `FIVE_MINUTES` 보유)을 받는 오버로드를
   **추가**하는 방식으로 구현 — 기존 1분 전용 메서드·정적 상수는 그대로 두고 내부에서
   `BinanceKlineInterval.ONE_MINUTE`로 위임하게만 바꿔 기존 호출부(BinanceKlineTempSyncService·
   BinanceKlineGapService·ManualBackfillService)는 시그니처·동작 변경 없음(순수 additive).
   `BinanceKlineRangeFetcherTest`·`BinanceKlineRestClientTest`·`BinanceKlineWindowTest`에 5분
   케이스 테스트 추가, `BinanceKlineTempSyncServiceTest`도 내부 `fetch()`가 이제 5-인자
   `fetchPage`를 호출하도록 바뀌어 mock stub을 함께 갱신(계획 원문의 "테스트 3종"에
   빠져 있었으나 실제로는 4번째 필요 — grep으로 확인 후 반영). `compileJava`+
   `compileTestJava` 통과, 관련 6개 테스트 클래스 29개 전부 통과.
   `gitnexus_detect_changes` risk=high로 나오나(공유 심볼이라 여러 execution flow에
   걸침), 전부 additive라 기존 1분 경로 동작은 바뀌지 않음(테스트로 확인).

4. **[완료, 2026-09-04] additive migration + canonical repository를 추가한다.**
   `V11__add_binance_kline_5m.sql` — `binance_kline_5m` 표, unique key/인덱스 순서 모두
   V10과 동일(`symbol, market_type, candle_time_ms`). `BinanceKline5m` entity(decimal
   precision(30,16)은 `BinanceKlineResponseParser.MAX_DECIMAL_PRECISION/SCALE`과 일치) +
   `BinanceKline5mRepository`(temp repository와 동일한 쿼리 메서드 2개) 신설. 기존 코드
   어디서도 아직 참조하지 않는 순수 additive라 기존 인스턴스는 이 표의 존재를 몰라도 정상
   기동한다. `compileJava` 통과.
    **실제 DB 적용 완료** — 배포 후 Flyway 적용과 canonical 표의 4개 조합 적재를 직접
    확인했다(상세는 아래 진행 상태와 기준선 측정 결과 참고).

5. **[완료, 2026-09-04] writer·sync를 5분 저장으로 구현하되, 정상 tail과 gap 리필을 별도
   상태로 분리한다.**
   `BinanceKline5mWriter`(temp writer와 동일한 INSERT IGNORE 패턴) + `BinanceKline5mSyncService`
   신설. v2가 말하던 "tail과 gap을 별도 경로로"가 아니라 계획 원문이 이후 수정한 대로
   "정상 진행과 장애 복구를 같은 리필 경로로" 구현 — 매 회차 최근 48시간의 "있어야 할
   5분봉 시각 집합"과 DB 시각 집합을 비교해 빠진 구간만 채운다(tail도 결국 "가장 최근의
   gap"이라 별도 코드가 필요 없어짐).
   - `RefillResult(expected, fetched, inserted, presentAfter, remainingGap, leaderLostMidRun)`
     반환 — 계획이 요구한 5개 지표 + leader 이탈 여부.
   - 인접한 빠진 5분 시각을 하나의 REST 범위로 병합(`mergedRanges`).
   - 회차당 최대 `MAX_RANGES_PER_CYCLE=20`개 gap-range만 시도, 넘으면 다음 회차로 미룸.
   - `leaderElectionService.isLeader()`를 **range마다** 재확인 — 상실 시 남은 range는
     건너뛰고 `leaderLostMidRun=true` 반환.
   - 429/5xx만 최대 3회 지수 백오프 재시도(`HttpStatusCodeException` 상태코드로 판별),
     그 외 4xx는 즉시 포기(재시도해도 성공할 리 없음).
   - `manualRefill()` — 비리더 인스턴스가 호출하면 즉시 `IllegalStateException`(기존
     `ManualBackfillService.collectKline1m`가 안고 있던 "팔로워가 writer 직접 호출" 결함을
     이번에 신설하는 5분 경로에는 처음부터 안 만듦 — 기존 1분 경로는 이번 세션에서 손 안 댐).
   - 테스트 11개(writer 2 + sync service 9) 전부 통과. `compileJava`+`compileTestJava` 통과,
     binance 도메인 전체 207개 테스트 통과(회귀 없음).
   - **아직 스케줄러가 실제로 돌지 않음** — 새 표(`binance_kline_5m`)가 실제 DB에 없는 한
     이 서비스의 `@Scheduled` 는 배포돼도 매 회차 빈 결과만 쌓다 다음 마이그레이션 배포
     후부터 진짜 채우기 시작한다. 6단계(1분·진행봉 경로 보존) 전까지는 기존 1분 temp
     쓰기를 끄지 않는다(계획 원칙).
   - **커밋 전 Codex commit-check(xhigh)로 2건 수정**: (1) `syncScheduled()`에서
     `statusRepository.findByEnabledTrue()` 조회 자체가 개별 try 밖에 있어 실패 시 회차
     전체가 예외로 끝나던 것을 별도 try로 감싸 격리. (2) 리더 확인이 REST 호출 직전에만
     있고 쓰기 직전엔 없어, REST 왕복(재시도 포함) 중 리더가 바뀌어도 옛 리더가 그대로
     `writer.insertIgnore()`를 실행할 수 있던 것을 쓰기 직전에도 재확인하도록 보강(범위를
     넘는 epoch-fence 검증까지는 안 함 — 기존 1분 sync/gap 서비스도 안 쓰는 수준이라
     과잉이라 판단, 필요해지면 별도로). 보완 성격의 interrupt-중단 개선도 함께 반영. 테스트
     2개 추가(격리 확인, 쓰기 직전 리더 상실 시 insertIgnore 미호출 확인).

6. **[완료, 2026-09-04] 1분·진행봉 경로를 먼저 보존한다(1분 temp 쓰기를 끄기 전에 반드시
   통과).**
   결론부터: **새 코드가 필요하지 않았다** — 검증 결과 두 가지가 이미 구조적으로 보존돼
   있었다.
   - `DataIntegrityEvaluator`(60분)·`AnalysisDetectionScheduler`(1440분) 재확인 결과 둘 다
     `SignalCandleSource.Interval.ONE_MINUTE`만 쓴다(`FIVE_MINUTES` 미사용). 이번 v3는
     ONE_MINUTE 읽기 경로(`combineTempOneMinute`)를 애초에 바꾸지 않으므로(치명 1 해결책
     — "50분 이하 REST" 설계 폐기, 1분 temp 전량 유지) 구조적으로 영향 없음. 기존 테스트
     (`DataIntegrityEvaluatorTest`·`AnalysisDetectionSchedulerTest`) 7개 그대로 통과 확인.
   - `CandleStreamService`의 IN_PROGRESS 5분·15분은 1분 temp 기반 부분집계(`findTempFive`)
     에 의존하는데, 1분 temp 쓰기를 끄지 않으므로 입력이 사라지지 않는다(치명 4 해결책).
     7단계에서 `BinanceKlineSignalCandleSource`를 고칠 때 COMPLETED 5분만 canonical로
     라우팅하고 IN_PROGRESS는 지금 경로를 그대로 남기면 된다 — 그 자체가 "보존"이라
     이 단계에서 미리 만들 코드가 없다.
   - "50분 이하 REST 경로"는 위에서 설계 자체를 폐기했으므로 이 항목은 대상이 없어짐.

7. **[구현 완료, 배포 후 확인 필요, 2026-09-04] source 읽기 전환 — PatternMatch·AnalysisSearch 포함.**

   착수 전 Codex xhigh 검수(계획 합의)로 발견: canonical 표는 매 회차 최근 48시간
   롤링 윈도우만 유지해서(4단계 참고), cutover~48시간 전 사이 **487개 5분봉이 canonical에
   없었다**(legacy 5분 표는 있음, temp 1분 표에서도 커버됨). 검수 결과 (A) 착수 전
   백필을 먼저 하기로 확정(REST 호출 4~12건 수준, 안전) — 하이브리드 폴백(B)은 복잡도만
   늘고 되돌리기도 더 비싸 기각.

   - **[완료] 백필 진입점 신설**: `BinanceKline5mSyncService.manualBackfillRange()` —
     기존 `refillNow()`의 gap-merge/재시도/leader 안전성 로직을 `refillRange()`로 공통
     추출해 재사용(임의 범위, 48시간 상한 검증 포함). `ManualBackfillService`에
     `KLINE_5M` 타입 추가(`POST /api/admin/backfill/collect`, 기존 `KLINE_1M`과 같은
     비동기 job 패턴) — 새 컨트롤러 불필요, 기존 admin API 재사용.
   - **[완료, 2026-09-04] 실제 백필 실행** — 맥미니 원격 세션이 리더 인스턴스에서 4건 실행,
     DB 직접 재확인 완료: 4개 조합 전부 정확히 487/487, `binance_kline_5m` 전체가
     2026-08-31 12:50부터 지금까지 완전히 연속(gap 0). 실행 중 관리자 화면
     `ManualCollectCard.jsx`에 `KLINE_5M` 옵션이 없어 첫 시도가 실수로 `KLINE_1M`으로
     들어가 153건 삽입됐으나, 해당 구간이 이미 1분 temp에 전량 있어 무해(INSERT IGNORE로
      중복 무시) — 확인 완료. `ManualCollectCard.jsx`에 `KLINE_5M` 옵션을 추가하고
      KLINE_1M·KLINE_5M 공통 시간 범위 검증을 적용했다.
   - **[완료] `BinanceKlineSignalCandleSource` 5분 COMPLETED 읽기를 canonical로 전환**.
     `findTemp()`가 `QueryMode.COMPLETED`면 `findCanonicalFive()`(canonical SPOT+FUTURES
     병합, 기존 `findTempFive()`와 동일 계약 — FUTURES 있어야 캔들 생성, SPOT은 delta에만
     가산)를, `IN_PROGRESS`면 기존 `findTempFive()`(1분 temp 기반)를 그대로 쓰도록 분기.
     15분은 자바 `aggregate()`를 canonical-소스 5분 리스트에 그대로 적용(SQL GROUP BY
     전환은 이번엔 보류 — 아래 "미확정 사항 갱신" 참고).
   - **[완료] 무제한 조회 위험 완화**: `AnalysisTemplateService.getDelta()`에 5분/15분
     한정 90일 상한 추가(Codex 지적 — canonical 전환 후에도 `getDelta`가 여전히 무제한
     범위를 받아 15분 자바 집계에 넣을 수 있어 512MB 힙 위험). 컨트롤러는 400으로 응답.
    - **[완료] `PatternMatchService` 전환** — `SignalCandleSource`의 공통 `SignalCandle`
      계약을 사용해 legacy/canonical 경계를 source 내부에 둔다. 선물 가격 기준과 현물·선물
      delta 합산을 기존 source 계약으로 통일하고, market_type 없는 직접 조회를 제거했다.
    - **[완료] `AnalysisSearchService` 전환** — 1m/5m/15m 모두 `SignalCandleSource.find()`를
      사용한다. 7일 단위로 나눠 읽고 이전 봉을 다음 구간으로 넘겨 LAG lookbehind 의미를
      보존한다. 전환 후 호출되지 않는 repository 유사 검색 메서드는 삭제했다.
    - 검증 endpoint: `/api/signal/pattern`·`/api/signal/score`·`/api/analysis/search`는
      코드 전환과 회귀 테스트까지 완료했고, 운영 endpoint 확인은 배포 후 진행한다.
    - **[완료, 2026-09-04] `/api/signal/candles` 5분·15분 실운영 검증** — 맥미니 원격 세션이
     72시간 범위로 조회: 5분봉 861개(간격 이상 0건, null/0-캔들 0건), 15분봉 286개(동일)
      — 백필 경계 포함 완전 연속 확인.
    - **[기존 배포 확인 완료, 새 정리 코드 배포 후 재확인 필요] `/api/analysis/delta`·
      `/ws/candle/5m`·`/ws/candle/15m`** — 기존 배포에서 delta API는 5분 21개·15분 6개를
      HTTP 200으로 반환했고 각 간격은 연속이었다. 두 웹소켓 모두 연결 후 메시지를 수신했다.
      15분 진행봉은 확인 시점에 첫 5분 구간이라 5분 진행값과 같았으며, 새 코드 배포 뒤
      심볼 계약·검색 경로와 함께 다시 확인한다.
   - 테스트: `BinanceKlineSignalCandleSourceTest`(canonical 병합·SPOT 결측·내부 gap·15분
     완결 조건·findBefore 케이스 추가) + `BinanceKline5mSyncServiceTest`(백필 케이스 6개
     — 경계 정렬·안전지연·in-flight 포함) + `ManualBackfillServiceTest`(KLINE_5M 케이스
      3개) + `AnalysisTemplateServiceTest`(신규, 90일 상한 4개 케이스) + 공통 심볼·검색
      테스트. springboot 전체 테스트 스위트(438개) 통과.
   - **커밋 전 Codex commit-check(xhigh)로 결함 3건 추가 수정**: (1) 백필이 스케줄러
     tick과 겹쳐 in-flight 충돌이 나도 `expected=0/remainingGap=0`이라 "성공"으로
     잘못 기록되던 것 — `RefillResult`에 `skippedInFlight` 필드 추가, 충돌 시 job을
     명시적 ERROR로(기존 `BinanceKlineTempSyncService.RangeSyncResult.skippedInFlight()`
     와 같은 패턴). (2) `manualBackfillRange()` 자체가 5분 경계·48시간 상한·"아직 안전
     지연 이전(진행 중인 봉)" 검증을 스스로 하도록 보강(이전엔 컨트롤러 쪽에만 있어
     다른 호출부에서 우회 가능했음). (3) `AnalysisTemplateService`의 90일 상한 계산을
     `Math.subtractExact`로 오버플로 안전하게.
   - **[운영 절차, 코드 아님]** KLINE_5M 관리자 백필은 **리더 인스턴스에서만** 성공한다
     (팔로워면 즉시 예외 — 리더 자동 전달 경로는 이번엔 안 만듦, 기존 KLINE_1M도 이
     기능 없음. 1회성 백필이라 운영자가 직접 리더를 확인하고 요청하면 됨. 상시 기능이
     필요해지면 `BinanceAnalysisLeaderForwarder`와 같은 패턴을 재사용해 별도로 추가).

   **백필 실행 방법** (배포 후, **리더 인스턴스**에 admin IP 허용 필요):
   ```
   POST /api/admin/backfill/collect
   {"type":"KLINE_5M","symbol":"BTCUSDT","marketType":"FUTURES",
    "fromMs":1788180600000,"toMs":1788326700000}
   ```
   위 body의 symbol/marketType만 바꿔 4번(BTCUSDT·ENAUSDT × FUTURES·SPOT) 호출.
   `GET /api/admin/backfill/status/{jobId}`로 `DONE`(inserted=487) 확인. 팔로워로 가면
   `ERROR`(리더가 아니라는 메시지) — 그 경우 다른 인스턴스로 재시도.
   백필이 실제로 끝나기 전까지는 최근 48시간보다 오래된 5분봉 조회(예: 프론트 168h/336h
   차트 프리셋)가 빈 결과를 반환할 수 있다 — 배포와 백필 실행 사이에 시차가 있으면
   일시적 현상.

8. **gap 관리자·운영 도구를 갱신한다.**
   `KLINE_1M`→`KLINE_5M` 표기 변경 여부 확정 후 `DataGapAdminService`·
   `DataGapAdminController`·`DataGapCard.jsx`·`ManualCollectCard.jsx` 함께 갱신. 48시간
   밖 검사를 유지한다면 chunk 분할·결과 합산을 추가(위 "높음" 참고). gap 탐지와 리필의
   책임을 분리하고, 관리자 조회마다 Binance를 무제한 호출하지 않도록 운영 제한을 둔다.

9. **dual-write·rollback을 검증한 뒤에만 cutover한다.**
   마이그레이션 배포 → canonical 백필 → old temp/canonical shadow 비교 → canonical+old
   temp 동시 쓰기 유지 → source read feature flag 전환 → 2인스턴스 rolling 상태 확인 →
   rollback artifact가 실제로 최근 구간을 읽는지 확인 → rollback 기간 종료 후 old temp
   보관 정책 확정. 이 순서를 코드/설정 변경 없이 그대로 실행 체크리스트로 쓴다.

10. **[신규, 2026-09-04 추가] 정리(cleanup) — 9단계 안정화 후, 롤백 기간이 끝난 뒤에만.**
    사용자 요청으로 명시적 단계로 추가. 아직 뭘 지울 수 있는지 확정된 목록이 아니라
    "이 시점에 다시 훑어봐야 할 것" 체크리스트다 — 지금은 아무것도 지우지 않는다.
    - **안 쓰는 코드**: PatternMatch·AnalysisSearch 전환 때 확인한
      `AggTrade5mRepository`/`AggTrade1mRepository`의 legacy 전용 유사 검색 메서드는
      호출처가 없어 이번 정리에서 삭제했다. `BinanceKlineFiveMinuteAggregator`는
      IN_PROGRESS(진행봉) 경로에서 계속 쓰이므로 유지한다.
    - **legacy 표(`agg_trade_1m`/`agg_trade_5m`)**: cutover 이후로는 안 쓰이지만(쓰기가
      이미 꺼짐), **pre-cutover 과거 데이터의 유일한 원본**이라 단순 삭제는 안 된다.
      PatternMatch의 "몇 개월~몇 년 치 과거 조회" 요구를 canonical/temp로 대체하지 않는 한
      계속 보관해야 한다 — 별도 아카이브(콜드 스토리지 이관 등)는 검토 가능해도 즉시 DROP
      대상 아님.
    - **`agg_trade_1m_temp`**: v3에서 "유지"로 확정(치명 1 해결책 — `DataIntegrityEvaluator`
      ·`AnalysisDetectionScheduler`가 1분 데이터로 계속 필요). **삭제 대상 아님** — 이름과
      실제 역할이 안 맞는 문제(v1 계획서의 원래 문제의식)는 표 이름을 바꾸는 것으로
      대응할지 이 시점에 다시 판단.
    - **관리자 화면/도구**: `ManualCollectCard.jsx`에 `KLINE_5M` 옵션을 추가하고
      KLINE_1M·KLINE_5M 공통 시간 범위 검증을 적용했다. 기존 KLINE_1M은 1분 진행봉
      원본이므로 임시 표기가 맞다.
    - **이 계획 문서 자체**: 맨 위 "죽음조건"대로, 이 단계가 끝나면
      `docs/binance/kline-temp-retire-plan.md`를 지운다(경위는 git 이력에 남음).

## 기준선 측정 결과 (2026-09-04, `springboot/.env` 로컬 자격으로 공유 DB 직접 조회)

`agg_trade_1m_temp`·`agg_trade_1m`·`agg_trade_5m`에 직접 SELECT(읽기 전용, `python`+`pymysql`)
로 확인. 쿼리와 원시 결과는 세션 스크래치패드 `baseline_query.py`에 보관.

- **대상 조합은 4개뿐**: `BTCUSDT`·`ENAUSDT` × `SPOT`·`FUTURES`. SPOT+FUTURES 유지 결정과
  일치 — 실제로 둘 다 계속 수집되고 있다.
- **temp 표 범위**: 조합당 8,107행, `candle_time_ms` 최소~최대가 약 8,106분(≈5.6일)로
  연속성 양호(빈 구간 거의 없음, 완전 연속이면 8,107행이 정확히 맞아떨어짐). cutover
  (`1788180800000`)보다도 이틀 앞서부터 데이터가 있다 — temp 수집이 cutover 전부터
  병행 검증용으로 먼저 시작됐던 것으로 보인다.
- **60분·1440분 실측**: 최근 60분 56행, 최근 1440분 1436행(기대치보다 각 4 적음) — 이는
  진짜 gap이 아니라 조회 시점(now)과 마지막 동기화 캔들 사이의 정상 지연(약 4분, 60초
  주기 sync + REST 응답 시간)이다. **DataIntegrityEvaluator·AnalysisDetectionScheduler를
  canonical로 옮길 때 이 정상 지연을 gap으로 오판하지 않는 경계 처리가 필요하다**(치명 1
  해결 시 반영).
- **legacy 표 정체 실측 확인(치명 2·3의 실측 근거)**: `agg_trade_1m`·`agg_trade_5m` 모두
  최신 `candle_time_ms`가 cutover 시각(2026-08-31 12:53:20 UTC) 근처에서 멈춰 있다 —
  측정 시점(2026-09-04) 기준 **약 3.44일째 새 행이 없다.** `PatternMatchService`·
  `AnalysisSearchService`가 이 표를 직접 읽으므로, canonical 전환 여부와 무관하게 **최근
  패턴 매칭·분석 검색은 이미 3.44일 전 데이터에 멈춰 있다** — 이 문제는 이번 세션 범위
  밖에서 이미 진행 중이었고, canonical 전환(PatternMatch·AnalysisSearch 포함 확정)이
  유일한 해결책임을 재확인.
- **24시간 합계 표본(향후 parity 비교 기준선)**: BTCUSDT/ENAUSDT × SPOT/FUTURES 각각의
  `SUM(quote_volume)`·`SUM(taker_buy_base_volume)`을 기록해뒀다(원시 값은 스크래치패드
  스크립트 실행 로그 참고) — 4단계 canonical 백필 후 같은 구간을 재계산해 대조한다.
- **AnalysisTemplateService.getDelta의 15분 임의 range — 미확정 사항 해소**: 프론트
  `useBinanceKlines.js`의 날짜 선택에는 UI 상한이 없고(`ControlBar.jsx` 확인, max/min
  제약 없음), `SignalCandleSource` 구현체는 cutover 이전은 SQL 기반 legacy 조회
  (`AggTrade5mRepository`), cutover 이후는 자바 `aggregate()`를 쓴다. 즉 **자바 집계
  대상 범위는 "cutover 이후 경과 일수"에 비례해 계속 늘어난다** — 지금은 5.6일치
  (조합당 약 8,107행)라 자바로 충분하지만, 수개월~1년 뒤에는 legacy SQL 집계보다 훨씬
  커진다(예: 1년 후 조합당 약 365×288≈105,120 5분 행). **결론(자바 vs DB GROUP BY)**:
  단기적으로는 자바 `aggregate()`를 유지하고 `AnalysisTemplateService.getDelta()`에 90일
  상한을 둔다. 검색 경로는 7일 chunk로 나눠 읽어 범위 전체를 한 번에 적재하지 않는다.
  SQL GROUP BY 전환은 실제 조회량이 커져 병목이 확인될 때 별도 측정 후 검토한다.

## 검증 계획

- 전환 전: symbol·market_type별 temp 표의 시각 집합·OHLCV·taker buy·delta 표본까지 기록
  (count/min/max/sum만으로는 누락과 대체 행을 구분 못 함).
- 백필: 과거 구간을 canonical 5분으로 chunk 백필하며 기존 temp 값과 OHLCV·delta까지 일치
  확인(이론상 동일 — 5분 네이티브 봉의 정의 자체가 1분 5개의 open/close/high/low/합계).
- Shadow read: 일정 기간 기존 경로와 새 경로를 동시 조회해 결과 비교 후 전환.
- 장애 시나리오: REST timeout·빈 응답·부분 실패·리필 동시 실행·리더 아닌 인스턴스·
  cutover 경계·429 — "빈 결과=정상"과 "빈 결과=수집 실패"를 구분하는지 확인(8/31 사고
  재발 방지). 리필 성공 조건은 "expected == present_after"까지 확인(단순 inserted > 0 불충분).

## 기각한 방향 (v2와 동일)

- raw_agg_trade/agg_trade_1s 재활성화: SSD 쓰기폭주로 의도적으로 끈 경로, 제안하지 않음.
- kline 값을 레거시 `agg_trade_1m`/`5m`에 채워 넣기: 원시 체결 ID·매수/매도 체결 건수를
  복원할 수 없어 데이터 계약이 오염됨.
- 1분 표 신설: 저장할 실익이 없음(치명 1의 60분·1440분 요구는 REST 직접 조회 또는 별도
  버퍼로 해결 — 표로 다시 저장하는 것과는 다른 문제).

## 후속 검토 사항

- **15분 SQL GROUP BY 전환**: 현재는 자바 `aggregate()`와 `getDelta()` 90일 상한으로
  운영한다. 90일 상한이 실제 사용에 좁거나 조회량이 병목으로 확인될 때 별도 측정 후 검토한다.
- canonical 5분 표의 정확한 컬럼셋과 entity 설계는 실제 migration 작성 시 Binance kline
  REST 응답 필드를 다시 대조해 확정. → 4단계에서 확정 완료.
- GitNexus 인덱스는 2026-09-04 현재 커밋 기준으로 재생성했고, 변경 전 대상 심볼의 upstream
  영향 분석을 완료했다. 다음 코드 변경 전에도 같은 절차를 반복한다.
- **[완료] PatternMatchService·AnalysisSearchService의 canonical/temp 전환** — 두 서비스 모두
  `SignalCandleSource`를 사용하고, AnalysisSearch는 7일 단위 조회와 구간 간 이전 봉 전달로
  장기 조회의 힙 적재를 제한한다. 기존 legacy 유사 검색 메서드는 호출처가 없어 삭제했다.

## Codex 적대적 검수 최종 판단 (2026-09-04) 및 이후 진행 상태

원래 판단은 **보류**였다. 아래 8개 중 사용자 결정이 필요했던 것(2번, 그리고 Codex 목록에는
없었지만 같은 성격인 SPOT+FUTURES·`KLINE_5M` 표기)은 2026-09-04에 사용자가 확정했다
(위 "결정 완료"). 나머지는 사용자 결정 사항이 아니라 실행 순서 각 단계가 풀어야 할 설계
과제이므로, 착수 자체를 막지는 않는다 — **조건부 가능으로 전환, 1~7단계 구현 완료.
운영 배포 확인은 남아 있다.**

1. ~~1분 경로(60분·1440분) 보존 방식~~ — **해결(6단계)**: 새 코드 불필요, ONE_MINUTE
   읽기 경로를 애초에 안 건드리는 구조로 확정.
2. ~~PatternMatch·AnalysisSearch의 canonical 전환 포함 여부~~ — **해결(7단계)**
3. ~~진행봉(IN_PROGRESS) 경로 보존 방식~~ — **해결(6단계)**: 7단계에서 IN_PROGRESS를
   canonical로 안 옮기고 기존 1분-temp 경로에 남기기로 확정.
4. ~~interval 공통 계층(REST client·fetcher·window)의 5분 대응~~ — **완료(3단계)**
5. ~~리더 상실·인스턴스 간 동시성 처리~~ — **완료(5단계, `BinanceKline5mSyncService`)**
6. ~~expected/fetched/inserted/present_after/remaining_gap 리필 완결성 상태~~ —
   **완료(5단계, `RefillResult`)**
7. 48시간 밖 gap chunk 정책 — 실행 순서 8단계(미착수)
8. dual-write 종료 시각과 실제 rollback 테스트 — 실행 순서 9단계(미착수)

전체 검수 원문(근거 파일:줄 포함)은 세션 스크래치패드에 보관:
`codex-review-kline-temp-plan.md`(2026-09-04 세션, `2200d208-9150-47ef-a066-281aa993e04c`).
