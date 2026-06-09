# Binance 수집/집계

> 이 문서는 로컬 LLM(gemma-4-26b-a4b-it-mlx)이 소스 코드를 근거로 자동 생성했다. 검증 전 초안이다.

## 목차
- 개요 및 도메인 구조
- 실시간 데이터 수집 파이프라인 (AggTrade & RawTick)
- 데이터 롤업 및 백필 프로세스 (1s/1m/5m)
- 데이터 아카이빙 및 관리 (S3 & Data Gap)
- 시그널 분석 및 대시보드 서비스
- 실시간 브로드캐스트 (SSE & WebSocket)
- 시스템 모니터링 및 텔레메트리 (RawWriter)

## 개요 및 도메인 구조

이 도메인은 바이낸스(Binance)의 실시간 체결 데이터 및 시세 정보를 수집, 저장, 분석하고 이를 클라이언트에게 제공하기 위한 시스템입니다. 데이터의 생명 주기는 원시 데이터(Raw Data) 수집에서 시작하여, 1초/1분/5분 단위의 봉(Candle) 생성 및 롤업, 그리고 장기 보관을 위한 S3 아카이빙으로 이어집니다.

**1. 데이터 수집 및 파이프라인**
실시간 데이터는 WebSocket 스트림을 통해 유입됩니다. `AggTradeStreamService`는 리더 권한을 가진 노드에서 스트림을 연결하고, `AggTradeParser`를 통해 파싱된 데이터를 `AggTradeSink` 인터페이스를 구현한 각 서비스(`AggTradeStorageSink`, `AggTradeKafkaSink`, `AggTradeSseSink` 등)로 분배합니다. 수집된 데이터는 `AggTradeStorageService`를 통해 Redis 큐에 적재된 후, 설정된 주기에 따라 `AggTradeFlushScheduler`에 의해 DB로 배치 플러시됩니다. 또한, Kafka를 이용한 파이프라인(`AggTradeRawWriterConsumer`)을 통해 데이터를 소비하고, `AggTradeRawWriterService`를 통해 DB 적재 또는 DryRun 검증을 수행하는 구조를 가집니다.

**2. 데이터 집계 및 롤업 (Rollup)**
수집된 원시 데이터는 다양한 시간 단위의 봉으로 집계됩니다. `AggTrade1sRollupService`는 1초봉을 생성 및 교정하며, `AggtradeRollupService`는 이를 바탕으로 1분봉(`AggTrade1m`) 및 5분봉(`AggTrade5m`)을 생성합니다. 이러한 과정은 `AggTradeCollectStatus`를 통해 진행 상태가 관리됩니다. 과거 데이터의 공백을 메우기 위한 백필(Backfill)은 `AggTradeBackfillService`와 `OiBackfillService` 등을 통해 수행됩니다.

**3. 데이터 분석 및 시그널 서비스**
집계된 데이터는 시각화 및 패턴 분석을 위해 활용됩니다. `SignalDataService`는 캔들 데이터, 미결제약정(OI), 에너지 및 청산 이력 등을 조회하여 분석에 필요한 데이터를 제공합니다. `PatternMatchService`는 5분봉 트리거 감지 및 과거 유사 패턴 매칭을 담당하며, `SignalSseService`는 분석된 결과나 실시간 업데이트를 클라이언트에게 SSE(Server-Sent Events) 방식으로 브로드캐스트합니다.

**4. 관리 및 운영 도구**
시스템의 안정적인 운영을 위해 다양한 관리 API가 제공됩니다. `ArchiveAdminController`와 `S3ArchiveService`는 데이터의 S3 아카이빙 및 삭제를 관리하며, `DataGapAdminService`는 데이터의 시간적 공백을 탐지합니다. 또한, `ManualBackfillController`를 통해 수동 데이터 보정 및 백필 작업을 제어할 수 있습니다. 실시간 모니터링을 위해 `AggTradeRawWriterKafkaTelemetryService`와 같은 텔레메트리 서비스가 시스템 지표를 수집하고 관리합니다.

## 실시간 데이터 수집 파이프라인 (AggTrade & RawTick)

실시간 데이터 수집 파이프라인은 크게 두 가지 경로로 운영됩니다.

**1. AggTrade 데이터 수집 및 저장 파이프라인**
AggTrade 데이터는 WebSocket을 통해 유입되어 Kafka를 거쳐 DB에 저장되는 과정을 거칩니다.
- **데이터 유입 및 분배**: `AggTradeStreamService`는 리더 권한을 가진 노드에서 Binance AggTrade WebSocket 스트림을 연결하고, 수신된 메시지를 `AggTradeParser.parse()`를 통해 파싱한 후 등록된 모든 `AggTradeSink`로 분배합니다.
- **Kafka 파이프라인**: 
    - `AggTradeRawWriterConsumer`는 Kafka 메시지를 소비하는 엔트리 포인트로, 메시지 파싱 및 검증을 수행하며 `Agg립AggTradeRawWriterService`를 통해 DB 적재 또는 DryRun 검증 로직을 실행합니다.
    - `AggTradeRawWriterService`는 파이프라인 상태(`KafkaPipelineState`)에 따라 메시지를 배치로 파티셔닝하여 `AggTradeRawWriterBatchPartitioner`를 통해 처리하거나, `AggTradeRawWriterDryRunVerifier`를 통해 데이터 정합성을 검증합니다.
    - 잘못된 메시지는 `AggTradeRawWriterInvalidMessage`로 묶여 DLQ(Dead Letter Queue)로 전송됩니다.
- **저장 및 상태 관리**: 
    - `AggTradeStorageService`는 Redis 큐에 데이터를 적재하고, 리더 노드에서 `doFlush()`를 통해 DB로 배치 삽입을 수행합니다. 이 과정에서 `AggTradeConfigService`의 설정값을 참조하여 임계치를 체크하며, `updateCheckpoints`를 통해 수집 상태를 갱신합니다.
    - 데이터는 `RawAggTrade` 엔티티 형태로 `raw_agg_trade` 테이블에 저장됩니다.

**2. RawTick 데이터 수집 파이프라인**
실시간 틱(Tick) 데이터는 별도의 경로로 수집 및 저장됩니다.
- **수집 및 적재**: `RawTickStorageService`는 Redis 큐를 이용하여 실시간 RawTick 데이터를 수집합니다. 리더 노드는 `scheduleFlush()`를 통해 주기적으로 큐의 데이터를 `RawTick` 엔티티로 변환하여 DB에 배치 삽입합니다.
- **브로드캐스트**: 수집된 데이터는 `RawTickSseService`를 통해 클라이언트에게 SSE(Server-Sent Events) 방식으로 실시간 전송됩니다.

## 데이터 롤업 및 백필 프로세스 (1s/1m/5m)

데이터 롤업 및 백필 프로세스는 시간 단위별로 각기 다른 서비스와 로직을 통해 수행됩니다.

**1. 1초봉(1s) 생성 및 교정**
`AggTrade1sRollupService`가 1초 간격의 실시간 롤업(`rollup1s`)을 담당합니다. 최근 15분간의 빈 캔들을 실제 데이터로 교정하는 `correctRecentEmptyCandles` 기능을 포함하며, 비동기 백필 프로세스인 `catchUp`과 Redis 락을 이용한 전체 백필 루프인 `runCatchUp`을 통해 데이터 정합성을 유지합니다. 집계 시에는 `aggregate1sRaw`를 통한 실시간용 집계와 백필을 위한 `aggregateChunkRaw`가 사용됩니다.

**2. 1분봉(1m) 및 5분봉(5m) 생성**
`AggTradeRollupService`가 1분 및 5분봉 생성을 담당합니다. 매 분 실행되는 `rollup1m`과 매 5분 실행되는 `rollup5m` 스케줄러를 통해 데이터가 생성됩니다. 1초봉 백필이 완료된 후 실행되는 체이닝 로직인 `catchUp`과, 1분/5분봉 백필을 위한 `catchUp1m`, `catchUp5m`이 존재합니다. 

데이터 적재 시에는 `upsert1mReplacingBad` 및 `upsert5mReplacingBad`를 통해 DB에 Upsert를 수행하며, 롤업을 위해 `aggregateFrom1sCandles` 및 `aggregateFrom1mCandles`를 사용하여 하위 단위 캔들로부터 상위 단위 캔들을 집계합니다. 또한, 5분봉 생성을 위해 `get1mFirstPrice`와 `get1mLastPrice`를 사용하여 1분봉 가격을 참조합니다.

**3. 백필 및 데이터 보정 프로세스**
과거 데이터를 확보하기 위한 `AggTradeBackfillService`는 Binance REST API를 사용하여 과거 `aggTrades` 데이터를 백필합니다. 특정 심볼/마켓에 대한 `backfillOne` 및 안전한 실행을 위한 `runBackfillSafely`를 통해 데이터 적재가 이루어집니다.

수동 데이터 보정 및 관리는 `ManualBackfillService`를 통해 수행됩니다. 여기에는 원시 데이터 수집(`collectRawAggTrade`), 1분/5분 롤업 생성(`collectRollup1m`, `collectRollup5m`), Klines를 이용한 빈 캔들 채우기(`fillMissing1mWithKlines`), 미결제약정(OI) 수집(`collectOi`) 등의 작업이 포함됩니다. 또한, 데이터 정합성 확보를 위해 플랫(Flat) 데이터 삭제(`deleteFlatData`), 캔들 보정(`correctFlatCandles`, `rebuildFlat1mWithKlines`), 이상치(Outlier) 교정(`correctOutlierCandles`, `rebuildOutlier1mFromRaw`) 등의 정교한 보정 로직이 제공됩니다.

## 데이터 아카이빙 및 관리 (S3 & Data Gap)

`RawAggTradeRepository`를 활용하여 `raw_agg_trade` 데이터를 관리하며, `RawAggTradeArchiveScheduler`는 CPU 사용량 및 보존 기간을 고려하여 스케줄링된 아카이빙 작업을 수행합니다. `S3ArchiveService`는 데이터를 CSV 파일로 작성(`writeToCsvTempFile`)하고 S3에 업로드(`uploadAndLog`)한 후, DB에서 배치 삭제(`deleteInBatches`)하는 전체 프로세스를 담당합니다. 아카이빙 이력은 `S3ArchiveLogRepository`를 통해 `s3_archive_log` 엔티티로 관리됩니다.

데이터의 무결성을 확인하기 위해 `DataGapAdminService`는 SQL을 사용하여 데이터 공백을 탐지합니다. 구체적으로 `agg_trade_id`의 연속성을 기반으로 하는 `rawAggTradeGap`과 캔들 시간 간격을 검증하는 `candleGap`, 그리고 Open Interest 데이터 수집 간격을 확인하는 `oiGap` 기능을 제공합니다. 또한, `ArchiveScanService`는 S3 버킷 내의 `.csv` 파일을 스캔하여 파일 메타데이터를 파싱하고, DB에 기록되지 않은 파일을 찾아 행 수를 계산하여 `s3_archive_log`에 저장하는 역할을 수행합니다.

## 시그널 분석 및 대시보드 서비스

시그널 대시보드 및 분석을 위한 데이터 제공과 실시간 브로드캐스트를 담당하며, 주요 구성 요소는 다음과 같습니다.

*   **데이터 제공 및 분석 서비스**: `SignalDataService`는 대시보드 초기화를 위한 데이터(`getInitData`), 에너지 및 청산 이력(`getHistoryData`), 미결제약정(OI) 이력(`getOiHistory`), 패턴 매칭 데이터(`findPatterns`)를 제공합니다. 또한 대규모 거래 임계값 계산(`calcLargeTradeThreshold`), 이동 평균 계산(`calcMovingAverage`), 패턴 매칭 결과에 따른 점수 및 보조 데이터 계산(`getScore`), 가격-델타 다이버전스 분석(`getDivergence`) 등 심도 있는 분석 기능을 수행합니다. `PatternMatchService`는 5분봉 트리거 감지 및 과거 유사 패턴 매칭/분석을 담당합니다.
*   **실시간 데이터 브로드캐스트**: `SignalSseService`는 SSE(Server-Sent Events)를 통해 거래 데이터(`broadcastAggTrade`), 청산 데이터(`broadcastForceOrder`), OI 데이터(`broadcastOiUpdate`), 분석 매칭 결과(`broadcastAnalysisMatch`)를 클라이언트에 실시간으로 전송합니다.
*   **설정 및 관리**: `SignalParamsRepository`를 통해 시그널 페이지 파라미터를 관리하며, `SignalController`는 `init`, `history`, `patterns`, `streamSse`, `getParams`, `getScore`, `getPattern`, `getCandles`, `getCandleDates`, `getOiHistory`, `getDivergence`, `putParams` 등의 API를 통해 대시보드 운영에 필요한 모든 엔드포인트를 제공합니다.

## 실시간 브로드캐스트 (SSE & WebSocket)

실시간 데이터 브로드캐스트는 SSE(Server-Sent Events)와 WebSocket을 통해 다양한 방식으로 수행됩니다.

**1. SSE (Server-Sent Events)를 통한 데이터 전송**
*   **체결 및 틱 데이터**: `BinanceTradeController`를 통해 실시간 체결 데이터를 제공하며, `BinanceTradeSseService`는 클라이언트 구독 관리 및 데이터 브로드캐스트를 담당합니다. 또한 `RawTickSseService`는 실시간 틱 데이터를 클라이언트에게 전송합니다.
*   **시그널 및 분석 데이터**: `SignalSseService`는 청산 데이터(`broadcastForceOrder`), OI(미결제약정) 업데이트(`broadcastOiUpdate`), 분석 매칭 결과(`broadcastAnalysisMatch`) 등을 클라이언트에게 브로드캐스트합니다.
*   **기타**: `AggTradeSseSink`는 파싱된 데이터를 SSE로 전송하며, `AggTradeStorageSink`는 이벤트를 저장소로 전달하는 역할을 수행합니다.

**2. WebSocket을 통한 데이터 전송**
*   **가격 및 티커 데이터**: `BinancePriceWebSocketHandler`는 프론트엔드 세션을 관리하며, 특정 심볼에 해당하는 세션들에게 JSON 메시지를 브로드캐스트합니다.
*   **캔들 데이터**: `CandleWebSocketHandler`는 심볼과 인터벌(1m, 5m) 조건에 맞춰 캔들 데이터를 브로드캐스트합니다.
*   **청산 데이터**: `ForceOrderStreamService`는 Binance Force Order(청산) WebSocket 스트림을 구독하여 데이터를 파싱하고, 이를 DB 저장 및 SSE 전송과 병행하여 처리합니다.
*   **스트림 관리**: `BinanceWebSocketStream`은 WebSocket 연결, 재연결 및 메시지 수신 중단(stale) 감시 기능을 제공합니다. `BinanceStreamService`는 실시간 티커 데이터를 구독하고 클라이언트 세션에 브로드캐스트합니다.

## 시스템 모니터링 및 텔레메트리 (RawWriter)

`AggTradeRawWriterKafkaTelemetryService`는 시스템의 다양한 지표를 수집, 저장 및 관리하는 핵심 텔레메트리 서비스입니다. 이 서비스는 소비 건수(`recordConsumed`), 쓰기 성공 건수(`recordWriteSuccess`), 무효 레코드 발생(`recordInvalidRecord`), DLQ 발행(`recordDlqPublished`, `breakrecordDlqPublishFailure`), DB 작업 실패(`recordDbFailure`), 재시도 성공 건수(`recordRetrySuccess`), 배치 처리 실패(`recordFailedBatch`) 등 파이프라인 전반의 지표를 기록합니다.

지표 관리를 위해 `AggTradeRawWriterTelemetryBucketStore`를 사용하여 시간 기반 버킷에 데이터를 저장하며, 특정 범위의 윈도우 데이터 생성(`windows`), 전체 버킷 스캔을 통한 요약(`summarize`), 그리고 상태 복구(`restore`) 기능을 제공합니다. 또한, `AggTradeRawWriterKafkaOffsetInspector`를 통해 Kafka의 토픽 및 파티션별 오프셋 상태(래그 포함)를 조회하여 텔레메트리 정보에 반영할 수 있습니다.

최종적으로 시스템 상태는 `AggTradeRawWriterKafkaTelemetryResponse`를 통해 전체 텔레메트리 스냅샷 형태로 제공되며, `AggTradeRawWriterKafkaTelemetryWindowsResponse`를 통해 시간 윈도우별 지표 목록을 확인할 수 있습니다. 내부적으로는 `KafkaPipelineSwitchboard`와 연동되어 동작하며, `AggTradeRawWriterFailureSampleBuffer`를 통해 발생한 실패 샘플들을 관리합니다.
