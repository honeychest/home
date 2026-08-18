# Binance 도메인 (수집·집계·시그널 · 백엔드)

> wiki-refresh로 갱신함(2026-06-24). 클래스/파일명은 현재 소스 트리(104개 .java)와 대조해 확인했고, 세부 동작 서술은 기존 생성본을 정리한 것이다(라인 단위 재검증은 일부만 수행). 페이지 간 관계는 `index.md` 참고.

## 한 줄 요약
바이낸스의 실시간 체결(aggTrade)·틱·청산·미결제약정(OI)을 수집해 1초/1분/5분봉으로 롤업·백필하고, S3 아카이빙과 데이터 공백 점검을 거쳐, 시그널 대시보드용 데이터를 SSE/WebSocket으로 실시간 브로드캐스트하는 가장 큰 백엔드 도메인이다.

## 이런 걸 물을 때 찾으면 된다 (검색 키워드)
- "바이낸스 체결 데이터 수집 / aggTrade 파이프라인 / Kafka"
- "1분봉 5분봉 롤업 / 백필 backfill / 빈 캔들 보정"
- "OI 미결제약정 / 청산 forceOrder / 델타 delta"
- "S3 아카이빙 / 데이터 갭 / 오래된 데이터 삭제"
- "시그널 대시보드 SSE / 실시간 시세 WebSocket / 캔들 스트림"
- "raw_agg_trade / RawTick / 텔레메트리 RawWriter Kafka"

## 핵심 개념·용어
- **aggTrade(집계 체결)**: 바이낸스가 같은 가격 체결을 묶어 보내는 스트림. 원시 저장은 `RawAggTrade`(테이블 `raw_agg_trade`).
- **롤업(rollup)**: 하위 단위 봉을 모아 상위 봉을 만드는 것. 1초→1분→5분.
- **백필(backfill)**: 누락 구간을 REST(klines/aggTrades)로 사후 채우는 것.
- **델타(delta)**: 매수 체결량 − 매도 체결량. 매수/매도 압력 지표(Analysis 도메인이 소비).
- **OI(Open Interest, 미결제약정)**: 청산되지 않은 선물 포지션 총량.
- **청산(Force Order)**: 강제 청산 체결 이벤트.
- **리더 노드**: 다중 인스턴스 중 수집/스케줄을 실제 수행하는 한 노드(`LeaderElectionService`). 중복 수집 방지.
- **SSE / WebSocket**: 서버→클라 단방향 실시간 전송(SSE)과 양방향 소켓(WebSocket).

## 구조 / 흐름 (클래스명은 소스 트리로 확인)

파일 위치: `springboot/src/main/java/com/chs/springboot/domain/binance/`

### 1) 실시간 수집 파이프라인 (AggTrade & RawTick)
- `AggTradeStreamService`: 리더 노드에서 Binance aggTrade WebSocket 연결, `AggTradeParser.parse()`로 파싱 후 등록된 모든 `AggTradeSink` 구현체로 분배.
  - Sink 구현: `AggTradeStorageSink`(저장 큐), `AggTradeKafkaSink`(`AggTradeKafkaProducer`), `AggTradeSseSink`(SSE), `AggTradeDebugSink`.
- Kafka 경로(`service/rawwriter/`): `AggTradeRawWriterConsumer`(소비 진입점) → `AggTradeRawWriterService`(파이프라인 상태 `KafkaPipelineState`/`KafkaPipelineSwitchboard`에 따라 `AggTradeRawWriterBatchPartitioner`로 배치 적재 또는 `AggTradeRawWriterDryRunVerifier`로 정합성 검증). 잘못된 메시지는 `AggTradeRawWriterInvalidMessage`로 묶어 DLQ 전송.
- 저장: `AggTradeStorageService`가 Redis 큐 적재 후 리더 노드에서 `doFlush()`로 DB 배치 삽입(`AggTradeConfigService` 임계값 참조, `AggTradeFlushScheduler` 주기 플러시). 수집 상태는 `AggTradeCollectStatus`.
- RawTick 경로: `RawTickStorageService`(Redis 큐 → 배치 삽입 `RawTick`), `RawTickSseService`(SSE 전송).

### 2) 롤업·백필 (1s / 1m / 5m)
- `AggTrade1sRollupService`: 1초봉 생성/교정(`rollup1s`, `correctRecentEmptyCandles`, `catchUp`/`runCatchUp`은 Redis 락).
- `AggTradeRollupService`: 1분봉(`rollup1m`, 매분)·5분봉(`rollup5m`, 매 5분). 하위→상위 집계(`aggregateFrom1sCandles`, `aggregateFrom1mCandles`), upsert(`upsert1mReplacingBad`/`upsert5mReplacingBad`), 백필 체이닝(`catchUp1m`/`catchUp5m`).
- 모델: `AggTrade1s`, `AggTrade1m`, `AggTrade5m`, `AggTradeCandle`.
- 백필/보정: `AggTradeBackfillService`(REST aggTrades), `OiBackfillService`(OI), `ManualBackfillService`(수동: `collectRawAggTrade`, `fillMissing1mWithKlines`, `deleteFlatData`, `correctFlatCandles`, `correctOutlierCandles` 등). `OpenInterestPollingService`(OI 폴링).

### 3) 아카이빙·데이터 점검 (S3 & Data Gap)
- `RawAggTradeArchiveScheduler`: CPU·보존기간을 고려한 S3 아카이빙 로직. 현재 자동 스케줄은 등록하지 않으며, S3 재연결 시 복구할 수 있다.
- `S3ArchiveService`: CSV 작성(`writeToCsvTempFile`) → S3 업로드(`uploadAndLog`) → DB 배치 삭제(`deleteInBatches`). 이력은 `S3ArchiveLog`(`s3_archive_log`).
- `ArchiveScanService`: S3의 `.csv`를 스캔해 DB 미기록 파일을 보정.
- `DataGapAdminService`: 데이터 공백 탐지(`rawAggTradeGap`/`candleGap`/`oiGap`). 컨트롤러 `ArchiveAdminController`, `DataGapAdminController`, `ManualBackfillController`, `AggTradeAdminController`.

### 4) 시그널 분석·대시보드
- `SignalDataService`: 대시보드 초기화(`getInitData`), 히스토리(`getHistoryData`), OI 이력(`getOiHistory`), 패턴(`findPatterns`), 임계치(`calcLargeTradeThreshold`), 이동평균(`calcMovingAverage`), 점수(`getScore`), 다이버전스(`getDivergence`).
- `PatternMatchService`: 5분봉 트리거 감지·과거 유사 패턴 매칭.
- `SignalController`(`/init`, `/history`, `/patterns`, `/streamSse`, `/getParams`/`putParams`, `/getCandles`, `/getOiHistory`, `/getDivergence` 등), 파라미터는 `SignalParams`/`SignalParamsRepository`.

### 5) 실시간 브로드캐스트 (SSE & WebSocket)
- SSE: `SignalSseService`(`broadcastAggTrade`/`broadcastForceOrder`/`broadcastOiUpdate`/`broadcastAnalysisMatch`), `BinanceTradeSseService`, `RawTickSseService`.
- WebSocket: `BinancePriceWebSocketHandler`(심볼별 티커 브로드캐스트), `CandleWebSocketHandler`(심볼·인터벌 1m/5m 캔들), `BinanceStreamService`(티커 구독). 청산 스트림 `ForceOrderStreamService`(`ForceOrder`). 연결/재연결/stale 감시 공용 인프라 `BinanceWebSocketStream`(Upbit 도메인도 재사용).

### 6) 모니터링·텔레메트리 (RawWriter)
- `AggTradeRawWriterKafkaTelemetryService`: 소비/쓰기성공/무효레코드/DLQ발행/DB실패/재시도/배치실패 지표 기록. 버킷 저장 `AggTradeRawWriterTelemetryBucketStore`(windows/summarize/restore), 오프셋·랙 조회 `AggTradeRawWriterKafkaOffsetInspector`, 실패 샘플 `AggTradeRawWriterFailureSampleBuffer`. 응답 DTO `AggTradeRawWriterKafkaTelemetryResponse`/`...WindowsResponse`.

### 주요 모델·레포 (발췌)
- 모델: `RawAggTrade`, `RawTick`, `BinanceTrade`, `ForceOrder`, `OpenInterest`, `AggTrade1s/1m/5m`, `AggTradeCollectStatus`, `S3ArchiveLog`, `SignalParams`. 이벤트: `Candle1mCompletedEvent`, `CandleCompletedEvent`, `SymbolChangeEvent`.
- 레포: `AggTrade1mRepository`(`findTopNWithCombinedDelta`, `findAllSimilarCandles` — Analysis가 사용), `AggTrade5mRepository`, `RawAggTradeRepository`, `OpenInterestRepository`, `ForceOrderRepository`, `S3ArchiveLogRepository` 등.

## 연관 도메인
- `be-analysis`(1m/5m봉·delta·SignalSseService 소비), `be-upbit`(`BinanceWebSocketStream` 공용), 프론트 `fe-page-signal`/`fe-page-trade`/`fe-page-binance`/`fe-domain-binance`. 상세 관계는 `index.md`.
