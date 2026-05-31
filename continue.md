# 개발 서버 작업 인수인계

갱신: 2026-06-01 07:35 KST

## 배경

2026-05-31에 도메인을 `devcontext.duckdns.org`에서 `devcontext.net`으로 전환한 뒤 Binance 페이지에서 WebSocket 값이 수신되지 않는 문제가 있었다.

운영 서버에서 확인한 결과, 도메인/Nginx/Cloudflare WebSocket 핸드셰이크 문제는 아니었다.

- `wss://devcontext.net/ws/binance-price?symbol=BTCUSDT` 핸드셰이크는 `101 Switching Protocols`로 성공했다.
- 하지만 메시지가 오지 않았다.
- `/api/monitor/snapshot` 기준 `binance-ticker`, `binance-aggTrade`, `upbit` 피드가 모두 `DOWN`이었다.
- 마지막 수신 시각은 대략 `2026-06-01 00:02:10 KST` 부근이었다.
- `app1`, `app2` 재시작 후 `DOCKER2`가 리더를 획득했고 세 피드가 모두 `UP`으로 복구됐다.

## 운영 서버에 이미 적용한 조치

운영 DB에서 `alert_history.metric_type` enum을 확장했다.

이유:

- 코드의 `AlertHistory.MetricType`에는 아래 피드 알림 타입이 있다.
- 운영 DB enum에는 해당 값이 없었다.
- 피드가 `DOWN`일 때 `AlertService.evaluateFeedAlerts()`가 이 값을 저장하려다 `Data truncated for column 'metric_type'` 오류를 반복했다.

운영 DB에 적용한 SQL:

```sql
ALTER TABLE alert_history
MODIFY metric_type ENUM(
  'API_ERROR',
  'CPU',
  'DISK',
  'RAM',
  'REDIS_QUEUE',
  'FEED_BINANCE_TICKER',
  'FEED_BINANCE_AGG',
  'FEED_UPBIT'
) NOT NULL;
```

운영 확인 결과:

- `SHOW COLUMNS FROM alert_history LIKE 'metric_type';` 결과에 피드 enum 3개가 포함됐다.
- 이후 최근 로그에서 `Data truncated`, `SqlExceptionHelper`, `alert_history` 오류가 재발하지 않았다.
- 현재 피드 상태는 `binance-ticker`, `binance-aggTrade`, `upbit` 모두 `UP`이다.

## 개발 서버에서 먼저 해야 할 작업

### 1. Flyway migration 추가

운영 DB에는 직접 반영했지만 저장소에는 아직 반영되지 않았다.

추가할 파일:

```text
springboot/src/main/resources/db/migration/V6__extend_alert_history_metric_type.sql
```

파일 내용:

```sql
ALTER TABLE alert_history
MODIFY metric_type ENUM(
  'API_ERROR',
  'CPU',
  'DISK',
  'RAM',
  'REDIS_QUEUE',
  'FEED_BINANCE_TICKER',
  'FEED_BINANCE_AGG',
  'FEED_UPBIT'
) NOT NULL;
```

주의:

- 운영 DB에는 이미 같은 변경이 적용되어 있다.
- Flyway가 다음 기동 때 같은 `MODIFY`를 실행해도 같은 enum 정의로 재정의하는 작업이라 데이터 삭제는 없다.
- 신규 DB나 다른 환경에서는 이 migration이 없으면 같은 저장 오류가 재발한다.

### 2. upstream stale watchdog 추가

근본 원인은 upstream WebSocket이 `onClose`/`onError` 없이 메시지만 멈춘 상태를 앱이 감지하지 못한 것이다.

현재 구조:

- `BinanceStreamService`는 `onClose`/`onError`에서만 재연결한다.
- `UpbitStreamService`도 `onClose`/`onError`에서만 재연결한다.
- `BinanceWebSocketStream`도 `onClose`/`onError`에서만 재연결한다.

추가해야 할 동작:

- 메시지 수신 시 마지막 수신 시각을 기록한다.
- 일정 시간 동안 메시지가 없으면 stale로 판단한다.
- stale이면 현재 WebSocket을 닫고 재연결을 예약한다.
- close/error 이벤트가 오지 않는 silent stale connection도 복구해야 한다.

권장 기준:

- `lastMessageAtNanos = System.nanoTime()` 사용.
- 메시지 수신 시마다 갱신.
- 10초마다 검사.
- 45초 이상 메시지가 없으면 stale로 판단.
- 기존 `reconnectPending`을 재사용해서 중복 reconnect를 막는다.
- stale 발생 시 로그와 `NotificationService.sendAlert()`를 남긴다.

수정 대상:

```text
springboot/src/main/java/com/chs/springboot/domain/binance/service/BinanceStreamService.java
springboot/src/main/java/com/chs/springboot/domain/upbit/service/UpbitStreamService.java
springboot/src/main/java/com/chs/springboot/domain/binance/service/BinanceWebSocketStream.java
```

우선순위:

1. `BinanceWebSocketStream.java`
2. `BinanceStreamService.java`
3. `UpbitStreamService.java`

이유:

- `BinanceWebSocketStream`은 `AggTradeStreamService`의 공통 upstream wrapper라 `binance-aggTrade` 복구에 직접 영향이 있다.
- Binance ticker와 Upbit ticker는 각각 별도 service라 별도 watchdog이 필요하다.

## 검증 명령

작업 디렉토리:

```bash
cd /Users/honey/devcontext/project/lab/springboot
```

특정 테스트:

```bash
./gradlew test --tests '*BinanceWebSocketStreamTest'
```

전체 테스트:

```bash
./gradlew test
```

빌드:

```bash
./gradlew bootJar
```

운영 또는 개발 서버 배포 후 확인:

```bash
curl -sS --max-time 8 http://127.0.0.1:8081/api/monitor/snapshot
```

확인할 값:

- `feeds[].feedId = binance-ticker` 상태가 `UP`
- `feeds[].feedId = binance-aggTrade` 상태가 `UP`
- `feeds[].feedId = upbit` 상태가 `UP`
- `secondsSinceLastMessage`가 계속 낮은 값으로 유지되는지 확인

WebSocket 직접 확인:

```bash
node -e "const url='wss://devcontext.net/ws/binance-price?symbol=BTCUSDT'; const ws=new WebSocket(url); const t=setTimeout(()=>{console.log('timeout no message'); ws.close(); process.exit(2)},12000); ws.onopen=()=>console.log('open'); ws.onmessage=e=>{console.log('message', String(e.data).slice(0,300)); clearTimeout(t); ws.close(); process.exit(0)}; ws.onerror=e=>console.log('error', e.message || e); ws.onclose=e=>console.log('close', e.code, e.reason);"
```

정상 기준:

- `open` 출력
- 12초 안에 `message {"e":"24hrTicker", ...}` 형태의 메시지 출력

## 커밋 권장 단위

1차 커밋:

```text
운영 알림 이력 metric_type enum 확장
```

포함 파일:

```text
springboot/src/main/resources/db/migration/V6__extend_alert_history_metric_type.sql
```

2차 커밋:

```text
실시간 피드 stale 재연결 감시 추가
```

포함 파일:

```text
springboot/src/main/java/com/chs/springboot/domain/binance/service/BinanceStreamService.java
springboot/src/main/java/com/chs/springboot/domain/upbit/service/UpbitStreamService.java
springboot/src/main/java/com/chs/springboot/domain/binance/service/BinanceWebSocketStream.java
springboot/src/test/java/com/chs/springboot/domain/binance/service/BinanceWebSocketStreamTest.java
```

## 현재 운영 상태 요약

- `chs-app-1`, `chs-app-2`: running
- `server:leader`: `DOCKER2`
- 리더 TTL: 정상 갱신 중
- `binance-ticker`: UP
- `binance-aggTrade`: UP
- `upbit`: UP
- `alert_history.metric_type`: 운영 DB enum 확장 완료
- 남은 근본 대응: stale watchdog 코드 반영
