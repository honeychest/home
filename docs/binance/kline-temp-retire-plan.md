> **죽음조건**: 이 계획을 실제로 구현·배포하면 이 문서를 지운다(경위는 git 이력에 남는다).
> 진행 상황·후속 요약은 `docs/binance/CONTEXT.md` "6. 아직 안 끝난 것"과 `docs/HANDOFF.md`를 본다.

# agg_trade_1m_temp 정식화 계획 v2 — 5분 네이티브 저장 + 리필 방식

작성: Claude (Codex v2 재작성 작업이 40분 넘게 응답 없이 멎어 취소하고 직접 정리함)
대상 저장소: C:\Users\Tissue\IdeaProjects\lab
범위: 계획만. 코드 미수정. signal-history 기능(완료됨)과는 무관.

## v1 대비 판단

v1(Codex 작성)은 "1분 canonical 표 + 5분 DB read model" 이원 구조를 권장했다. 이번 v2는
그 이원 구조를 없애고 **5분만 정식으로 저장**한다. 이유:

1. Binance kline API는 5분봉을 그 자체로 제공한다(`interval=5m`). 1분을 모아 5분을 만들
   필요가 없다 — 이미 이 저장소의 `LiveMarketDataService`(자동매매 분석용)가 1m·5m·15m·4h를
   인터벌별로 각각 직접 받는 선례가 있다(`docs/binance/CONTEXT.md` 3장).
2. signal 쪽에서 1분 데이터가 쓰이는 곳은 `SignalDataService.getHistoryData`의 energy 조회에서
   구간이 50분 이하일 때뿐이다(`SignalDataService.java`의 interval 스위치, "1m,5m,10m,30m,50m
   → ONE_MINUTE"). 50개 이하 캔들은 Binance REST 1회 호출로 즉시 오므로 저장할 이유가 없다.
3. 이원 구조를 없애면 v1이 필요로 했던 5분 read model 신설, 백필, 완결봉 검증 로직 전체가
   사라진다 — 변경 파일 수가 크게 줄어든다.

이 판단에 반박 여지가 있는 지점(구현 착수 전 재확인 필요): `SignalCandleSource.find()`가
지원하는 15분·4시간 인터벌은 지금 5분을 자바에서 다시 묶어 만든다(`BinanceKlineSignalCandleSource
.java:303-341`, v1이 인용). 5분을 네이티브로 저장해도 15분·4시간은 여전히 자바 또는 DB
집계가 필요하다 — 이건 v1과 v2 둘 다 동일하게 안고 가는 문제이므로 이번 결정에 영향 없음.

## 현재 상태 (v1에서 검증됐고 이번 대화에서 나도 독립적으로 확인한 사실만 유지)

- `agg_trade_1m_temp` 원래 계약: "임시 검증용, 레거시 롤업과 연결 안 함"
  (`V10__add_agg_trade_1m_temp.sql:1-2`).
- 2026-08-31 커밋 `33bc1e4`가 raw_agg_trade/레거시 롤업 쓰기를 껐고, 다음날 커밋 `831b94d`가
  `BinanceKlineSignalCandleSource`를 만들어 cutover 이전은 레거시(`agg_trade_1m`/`5m`),
  cutover 이후는 temp를 읽는 혼합 경로로 응급 복구했다. cutover 값
  `binance.agg-trade.legacy-cutover-ms=1788180800000` = 2026-08-31 12:53:20 UTC
  (내가 직접 계산해 검증함).
- temp 조회는 `BinanceKlineTempCandleRepository`가 페이지네이션·DB 집계 없이 전체 List를
  반환하고(`findBySymbolAndMarketTypeAndCandleTimeMsGreaterThanEqualAndCandleTimeMsLessThanOrderByCandleTimeMsAsc`),
  이를 `BinanceKlineFiveMinuteAggregator`가 자바에서 5분으로 합친다. 512MB 힙·2인스턴스
  제약(`springboot/AGENTS.md`)에서 조회 범위가 길어질수록 위험.
- 정기 쓰기: `BinanceKlineTempSyncService.syncScheduled()`(leader 전용, 60초 주기)가
  "마지막 저장 캔들 이후 ~ 지금"만 앞으로 채운다. 최대 48시간까지만 자동 caught-up
  (`BinanceKlineTempSyncService.java`, `MAX_RANGE_MS` 관련 로직).
- 별도 관리자 수동 도구: `BinanceKlineGapService` + `DataGapAdminService` — 지정 범위에서
  DB와 Binance REST의 시각 집합을 비교해 GAP을 "탐지만" 한다. 자동 복구 없음.

## GitNexus 영향 분석 (직접 실행, upstream, repo=C:\Users\Tissue\IdeaProjects\lab)

| 대상 | 위험 | 직접 의존 |
|---|---|---|
| `BinanceKlineFiveMinuteAggregator` | LOW | 3 (`BinanceKlineSignalCandleSource`, `BinanceKlineTempSyncService` 생성자 2곳) |
| `BinanceKlineTempSyncService` | LOW | 1 (`ManualBackfillService`) |
| `BinanceKlineGapService` | LOW | 1 (`DataGapAdminService`) |
| `BinanceKlineTempWriter` | LOW | 1 (`BinanceKlineTempSyncService`) |
| `SignalCandleSource`(인터페이스) | **MEDIUM** | 6 — `DataIntegrityEvaluator`, `SignalCandleAnalysisConverter`, `AnalysisTemplateService`, `AnalysisDetectionScheduler`, 구현체 `BinanceKlineSignalCandleSource`, 2단계로 `AnalysisTemplateController` |

**결론**: 실제로 보호해야 할 경계는 `SignalCandleSource` 인터페이스 계약
(`find`/`findBefore`/`findByQuoteVolume`/`findCandleDates`/`sumEnergy`의 파라미터·반환 의미)
하나뿐이다. 이 계약만 그대로 유지하면, 그 아래(aggregator·sync·writer·gap서비스)는
전부 LOW·의존 1곳이라 비교적 자유롭게 재작성할 수 있다. v2 실행 시 이 인터페이스를
바꾸지 않는 것을 최우선 제약으로 둔다.

## 목표 구조

~~~
Binance REST/WS kline (interval=5m, SPOT+FUTURES)
  -> canonical 5분 kline 표 (신규)
  -> SignalCandleSource 구현체 (계약 불변)
     - 15분/4시간은 지금처럼 5분을 다시 묶어서 계산(자바 또는 DB, 택1 — 아래 참고)
     - 50분 이하 짧은 구간만 그때그때 Binance REST 1분봉 직접 조회(저장 안 함)
  -> 기존 소비자 6곳 (변경 없음)
~~~

## 짧은 구간(≤50분) 1분 조회 처리안

| 방식 | 장점 | 단점 |
|---|---|---|
| 요청마다 Binance REST 직접 조회(권장) | 저장·정합성 관리 불필요, 코드 최소 | 매 요청마다 외부 API 호출 1회, Binance 응답 지연이 곧 API 응답 지연이 됨 |
| 소규모 인메모리 버퍼(기존 `LiveKlineBuffer`류 패턴 재사용) | 응답 빠름, 외부 호출 안 씀 | 2인스턴스 각각 별도 상태(팔로워는 비어있을 수 있음), 재시작 시 초기화, 새 컴포넌트 유지보수 부담 |

권장: REST 직접 조회. 50개 캔들 이하는 API 1회 호출로 충분히 빠르고, 새 상태를 안 만들어도
된다. 나중에 실측으로 느리면 그때 버퍼를 얹는다(지금 안 만들어도 되돌리기 싸다).

## 롤업 → 리필 전환

**지금**: 정기 sync(앞으로만 채움) + 관리자 수동 gap 탐지(사람이 발견해야 함), 완전히 분리.

**바꾼 뒤**: 정기 실행마다(leader 전용, 기존 60초 주기 유지)
1. 최근 N시간(기존 48시간 상한 재사용) 구간의 "있어야 할 5분봉 시각 집합"을 계산.
2. DB에 있는 시각 집합과 비교해 빠진 시각만 추출.
3. 빠진 구간만 Binance REST로 재조회해 채운다(기존 `inFlightRanges` 동시실행 가드 재사용).

이러면 정상 진행(마지막 이후 채우기)과 장애 복구(구멍 메우기)가 같은 코드 경로가 된다.

**48시간보다 오래된 과거 구멍**: 정기 리필은 안 건드린다(비용 대비 실효성 낮음). 대신
`BinanceKlineGapService`+`DataGapAdminService`(관리자 수동 도구)는 **없애지 않고 유지** —
"정기 리필이 못 따라잡는 오래된 구간을 사람이 필요할 때만 점검"하는 최후 수단으로 남긴다.
근거: 이 도구를 없애면 48시간 밖 이력 데이터의 무결성을 확인할 방법이 아예 없어진다.
단, 비교 대상 repository는 canonical 5분 표로 바꿔야 한다.

## 예상 변경 파일 (v1보다 적음 — 5분 read model 이원화가 없어서)

1. 신규 Flyway migration: canonical 5분 표 1개(symbol, market_type, candle_time_ms, OHLCV,
   quote_volume, taker_buy_base/quote_volume, unique key, 조회 인덱스). 기존 `agg_trade_1m_temp`는
   즉시 삭제하지 않고 보존(rollback 대비).
2. 신규 entity: `BinanceKline5m.java`(가칭) — 표 이름 확정 후 명명.
3. `BinanceKlineTempCandleRepository.java` → canonical 5분 repository로 교체 또는 신설
   (`BinanceKline5mRepository.java`).
4. `BinanceKlineTempWriter.java` → 5분 필드 INSERT로 변경.
5. `BinanceKlineTempSyncService.java` → REST 조회 interval을 5m으로, 롤업(1분→5분 자바 집계)
   제거, 위 리필 로직 추가. `BinanceKlineFiveMinuteAggregator.java`는 이 서비스 안에서
   더는 안 씀 — 삭제 또는 백필 검증 전용으로 격리.
6. `BinanceKlineSignalCandleSource.java` → temp 1분 조회·자바 5분 집계 로직 제거, canonical
   5분 직접 조회로 교체. **`SignalCandleSource` 인터페이스 시그니처는 그대로 유지.**
7. `BinanceKlineGapService.java` → 비교 대상을 canonical 5분 repository로 교체(로직 자체는 유지).
8. `ManualBackfillService.java` → KLINE_1M 백필 옵션을 5분 기준으로 재정의(이름 변경 포함 검토).
9. `application.properties`/`application-prod.properties` → interval 관련 설정 값 조정.
10. 관련 테스트 파일(`BinanceKlineTempSyncServiceTest.java`,
    `BinanceKlineSignalCandleSourceTest.java` 신규, `BinanceKlineGapServiceTest.java`,
    `ManualBackfillServiceTest.java`) 갱신.

v1에 있던 "5분 read model 신규 entity/repository/백필/완결봉 검증" 항목은 전부 제거됨.

## 검증 계획 (요지)

- 전환 전: symbol·market_type별 temp 표의 count/min/max/합계를 기록해둔다(비교 기준선).
- 백필: 과거 구간을 canonical 5분으로 chunk 백필하면서 기존 temp 데이터(1분을 자바로
  합친 값)와 새 canonical(Binance 네이티브 5분) 값이 OHLCV·delta까지 일치하는지 표본 비교.
  이론상 동일해야 함(5분 네이티브 봉의 정의 자체가 1분 5개의 open/close/high/low/합계이므로).
- Shadow read: 일정 기간 기존 경로와 새 경로를 동시에 조회해 결과 비교 후 전환.
- 장애 시나리오: REST timeout, 빈 응답, 부분 실패, 리필 동시 실행, leader가 아닌 인스턴스,
  cutover 경계 — 전부 "빈 결과=정상"과 "빈 결과=수집 실패"를 구분하는지 확인
  (8/31 사고 재발 방지가 핵심 목적).

## 실행 순서

1. 표 이름 확정(아래 후보 중 협의) + 5분 표 컬럼 계약 확정
2. 전환 전 측정(temp 표 기준선 저장)
3. additive migration + canonical repository/writer 추가
4. 과거 구간 chunk 백필 + 기존 값과 parity 검증
5. `BinanceKlineTempSyncService`를 5분 리필 방식으로 전환(dual write 기간 포함 가능)
6. `BinanceKlineSignalCandleSource`를 canonical 읽기로 전환(인터페이스 불변 확인)
7. `BinanceKlineGapService` 비교 대상 전환
8. 레거시 `agg_trade_1m_temp`는 rollback 기간 보관 후 별도 migration으로 정리

## 표 이름 후보 (최종 선택은 사용자와 협의)

~~~
가. binance_kline_5m
   "바이낸스에서 받은 5분봉 정식 원본" — 의미가 이름에 바로 드러남

나. kline_5m
   더 짧음. 이 프로젝트엔 바이낸스 외 다른 거래소 kline이 없어 접두사 없어도 혼동 안 될 듯

다. agg_trade_1m_temp 물리 이름 유지, 의미만 5분 kline 정식 표로 바꿈
   배포 리스크(구버전 인스턴스가 계속 옛 이름에 쓰려는 문제)는 피하지만
   "temp"·"1m"이라는 이름이 이후에도 계속 오해를 부를 수 있음
~~~

가안 권장(지난번과 동일한 이유 — 이름이 문서 역할).

## 기각한 방향

- raw_agg_trade/agg_trade_1s 재활성화: SSD 쓰기폭주로 의도적으로 끈 경로, 제안하지 않음.
- kline 값을 레거시 `agg_trade_1m`/`5m`에 채워 넣기: 원시 체결 ID·매수/매도 체결 건수를
  복원할 수 없어 데이터 계약이 오염됨(지난 대화에서 확정).
- 1분 표 신설: 저장할 실익이 없음(50분 이하 조회는 즉시 REST로 충분) — v2의 핵심 변경점.

## 미확정 사항 (구현 착수 전 재확인 필요)

- 15분·4시간 인터벌을 5분에서 다시 묶을 때 자바로 할지 DB GROUP BY로 할지 — 실측 없이는
  판단 근거 부족(v1의 "후보 C" 논의와 동일한 트레이드오프).
- canonical 5분 표의 정확한 컬럼셋과 entity 설계는 실제 migration 작성 시 Binance kline
  REST 응답 필드를 다시 대조해 확정.
