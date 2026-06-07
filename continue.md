# 작업 의뢰서 — AggTrade WebSocket 자동복구 실패 (4일 침묵)

작성: 2026-06-07 KST

## 1. 증상
- 6/3 21:10:39 마지막 trade DB 적재. 이후 6/7 서버 재시작까지 약 4일 침묵.
- 재시작 후 즉시 정상 수신.
- 로직 결함 아님. 재연결 메커니즘 영구 정지.

## 2. 영향 범위
- 운영 컨테이너: `chs-app-1` 단독 (수집은 leader-only, `chs-app-2` 는 stand-by)
- 영향 stream: `AggTradeStream/{BTCUSDT,ENAUSDT}/{SPOT,FUTURES}` 4종, `BinanceStream/ticker`, `UpbitStream/ticker` — 모든 외부 WebSocket 동시 마비

## 3. 확정 원인

### 3-1. 단일 HttpClient 공유로 인한 광역 영향 (가장 결정적)
- `BinanceWebSocketStream.java:23` `private static final WebSocketConnector SHARED_CONNECTOR = createSharedConnector();`
- `BinanceWebSocketStream.java:259-263` `HttpClient client = HttpClient.newHttpClient();` 정적 1회 생성
- → 6개 stream 인스턴스 전부 동일 JDK HttpClient 공유
- 호스트 외부 TLS 장애 시 client 내부 selector/connector 상태 손상 → 새 `buildAsync(...)` 가 `CompletableFuture` 반환은 하지만 영원히 미완료 (`whenComplete` 콜백 호출 0건 = 로그에서 `handshake 실패`, `오류 (gen=…)` 모두 0건으로 검증)
- JVM 재시작만이 복구 경로

### 3-2. 단일 스레드 ScheduledExecutorService 에 watchdog + reconnect + abort 적체
- `AggTradeStreamService.java:40-45` `Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "aggtrade-stream"))`
- `BinanceWebSocketStream.connect()` (line 114) `scheduler.execute(openStream)`
- `BinanceWebSocketStream.startWatchdog()` (line 120) `scheduler.scheduleAtFixedRate(checkStale, 10s, 10s)`
- `BinanceWebSocketStream.scheduleReconnect()` (line 256) `scheduler.schedule(connect, ...)`
- `BinanceWebSocketStream.checkStale()` (line 137) `currentWebSocket.abort()` 동기 호출
- → 호스트 단 socket 이 hung 이면 `abort()` 가 OS TCP keepalive(~2h) 만료까지 반환 안 함
- → 단일 스레드 점유 → fixedRate watchdog 다음 tick 지연 (10s 주기여야 하는데 4일간 실제 stale 감지 30건뿐, 평균 간격 ~3.2시간 — TCP keepalive 단위와 일치)
- `BinanceStreamService`, `UpbitStreamService` 도 각각 `pool-7-thread-1`, `pool-8-thread-1` 단일 스레드 풀 사용 추정 (동일 패턴 확인 필요)

### 3-3. checkStale 외곽 예외 처리 부재
- `BinanceWebSocketStream.java:124-143` `checkStale()` 전체 try/catch 없음
- `scheduleAtFixedRate` 태스크가 unchecked exception throw 시 후속 실행 영구 중단 (JDK 스펙, silent)
- 현 케이스 직접 사망 증거는 없지만(stale 30건 감지됨), abort/scheduleReconnect 내부 예외 발생 시 사망 위험 잠재

### 3-4. 운영 로그 레벨 WARN 으로 진단 가시성 부족
- `application-prod.properties` `logging.level.root=WARN`
- `BinanceWebSocketStream` 패키지 INFO 미지정
- `log.info("연결 시도/연결 성공")` 4일 내내 0건 = 실제 흐름 추적 불가능
- 운영자가 재연결 성공 여부 알 수 없음

### 3-5. 운영 알림 부재로 발견 지연
- `LogNotificationService` 호출이 stream pool 과 동일 스레드에서 실행 (`pool-7-thread-1` 로그 확인) → 풀 hang 시 알림 자체도 hang
- DB threshold 정체에 대한 외부 헬스체크 알림 경로 없음

## 4. 수정 작업 (우선순위順)

### P0 — 즉시 (광역 마비 차단)
- [ ] **4-1. HttpClient 인스턴스 stream 별 격리**
  - `SHARED_CONNECTOR` 제거. `BinanceWebSocketStream` 생성자에서 자체 `HttpClient.newHttpClient()` 또는 외부 주입
  - `BinanceWebSocketStream.java:23, 259-263` 수정

- [ ] **4-2. 연속 stale N회 시 HttpClient 재생성 + 객체 강제 재초기화 경로**
  - generation 증가만으론 hung 상태 HttpClient 복구 불가
  - 임계 초과 시 `HttpClient` 새 인스턴스로 교체

### P0 — 동시 (단일 풀 해체)
- [ ] **4-3. watchdog 전용 별도 ScheduledExecutorService**
  - 신규 single-thread executor (daemon, name `bws-watchdog`)
  - `startWatchdog()` 가 이 executor 사용. I/O 풀과 격리

- [ ] **4-4. reconnect 전용 별도 ScheduledExecutorService**
  - watchdog 과도 분리. reconnect/openStream 만 사용
  - `openStream` 의 `connector.connect(...).whenCompleteAsync(handler, reconnectExecutor)` 로 콜백 풀 명시

- [ ] **4-5. `webSocket.abort()` 비동기 + 타임아웃**
  - `CompletableFuture.runAsync(ws::abort, abortExecutor).orTimeout(3, SECONDS)`
  - abort 가 OS TCP timeout 까지 hang 해도 watchdog 스레드 반환 보장

### P1 — 안정성
- [ ] **4-6. `checkStale()` 전체 `try { ... } catch (Throwable t) { log.error(...) }` 로 감싸기**
  - scheduleAtFixedRate 사망 방지
  - `scheduler.execute`/`scheduler.schedule` 람다 모두 동일 패턴

- [ ] **4-7. `LogNotificationService` 별도 executor 로 분리**
  - 알림 hang/실패가 stream 풀에 역류하지 않게

- [ ] **4-8. `BinanceStreamService`, `UpbitStreamService` 의 scheduler 도 동일 점검**
  - 스레드명 `pool-7-thread-1`, `pool-8-thread-1` 로 단일 스레드 풀 추정
  - AggTradeStreamService 와 동일 패턴으로 분리·격리 적용

### P1 — 가시성
- [ ] **4-9. 운영 로그 레벨 조정**
  - `application-prod.properties` 에 `logging.level.com.chs.springboot.domain.binance.service.BinanceWebSocketStream=INFO` 추가
  - 연결 시도/성공 추적 가능하도록

- [ ] **4-10. 헬스체크 + 외부 알림**
  - `feedHealthRegistry` 기반 최근 수신 timestamp 액추에이터/모니터링 노출
  - N분(예: 3분) 이상 정체 시 stream 알림 채널과 독립된 경로(별도 텔레그램 봇 / 외부 cron) 로 통보

### P2 — 회귀 테스트
- [ ] **4-11. `BinanceWebSocketStreamTest` 케이스 추가**
  - `checkStale` 내부 throw 강제 → watchdog 살아있는지 검증
  - `abort` mock 5초 sleep → watchdog 다음 tick 정상 발화 검증
  - `connector.connect` 가 영구 pending → reconnect executor hang 해도 watchdog tick 유지 검증
  - HttpClient 격리 효과: 한 stream 의 connector 가 죽어도 다른 stream 영향 없음 검증

## 5. 배포 후 검증
- 운영 트래픽 6시간 모니터링: stale 로그 발생 시 후속 `연결 성공` 로그 페어 확인 (4-9 적용 후)
- 호스트 외부 네트워크 일시 차단 시뮬레이션 (10~120초) 후 60초 이내 자동 복구 확인
- DB threshold 마지막 timestamp 3분 이상 정체 없는지 헬스체크 알림 정상 발화 확인

## 6. 위험 라인 인덱스
- `BinanceWebSocketStream.java:23` `SHARED_CONNECTOR` static 단일 인스턴스
- `BinanceWebSocketStream.java:114` `scheduler.execute(openStream)` — 단일 풀
- `BinanceWebSocketStream.java:120` `scheduler.scheduleAtFixedRate(checkStale, ...)` — 단일 풀, try/catch 없음
- `BinanceWebSocketStream.java:124-143` `checkStale` 본문 — 외곽 예외 처리 부재
- `BinanceWebSocketStream.java:137` `currentWebSocket.abort()` — 동기, hang 위험
- `BinanceWebSocketStream.java:256` `scheduler.schedule(connect, ...)` — 단일 풀
- `BinanceWebSocketStream.java:259-263` `createSharedConnector` — `connectTimeout` 만, HttpClient 1개
- `AggTradeStreamService.java:40-45` `newSingleThreadScheduledExecutor("aggtrade-stream")` — 단일 스레드 풀, 4 stream 공유
- `application-prod.properties` `logging.level.root=WARN` + BinanceWebSocketStream INFO 미지정

## 7. 근거 로그 요약
- 6/3 21:00:25 `TelegramPollingService: Remote host terminated the handshake` (호스트 외부 TLS 장애 첫 신호)
- 6/3 21:10:39 마지막 trade DB 적재 (사용자 확인)
- 6/3 21:10 ~ 6/7 11:50 구간 `BinanceWebSocketStream` 이벤트: `stale 감지` 30건, `종료(gen=4/6, status=1006)` 2건, **`연결 성공`/`handshake 실패`/`오류` 0건**
- 같은 구간 Telegram polling 단속적 실패 약 30건 = 호스트 네트워크 단속 장애 (영구 장애 아님)
- 6/7 11:46 재시작 직후 동일 stale 패턴 → 정상 재연결 복구 확인
