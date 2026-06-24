# Upbit 도메인 (백엔드)

> wiki-refresh로 실제 소스를 읽고 검증함(2026-06-24). 페이지 간 관계는 `index.md` 참고.

## 한 줄 요약
Upbit 거래소 WebSocket(상위 스트림)에 **항상 연결되어 고정 코드 5종을 전체 구독**하고, 받은 실시간 시세를 프론트엔드 WebSocket 세션들에게 **세션별 요청 코드로 필터링**해 전달하는 백엔드 도메인이다.

## 이런 걸 물을 때 찾으면 된다 (검색 키워드)
- "업비트 시세는 어떻게 받아와? Upbit WebSocket 연결"
- "업비트 구독 코드 / KRW-BTC 등 어떤 코인 받아오나"
- "세션별 시세 필터링 / 접속 시 캐시 스냅샷 전송"
- "UpbitStreamService / UpbitPriceWebSocketHandler 역할"
- "UpbitSubscriptionChangeEvent 는 쓰이나?"

## 핵심 개념·용어
- **상위 스트림(upstream)**: 우리 서버가 Upbit 서버(`wss://api.upbit.com/websocket/v1`)에 직접 거는 단일 WebSocket 연결. 항상 연결(always-on).
- **고정 전체 구독**: 클라이언트 수와 무관하게 상위 구독 코드는 `KRW-BTC, KRW-ETH, KRW-SOL, KRW-XRP, KRW-USDT`로 고정.
- **클라이언트 세션**: 프론트엔드가 우리 서버에 거는 WebSocket. 접속 URL의 `?codes=` 쿼리로 받고 싶은 코드를 지정.
- **세션별 필터링**: 상위에서 받은 메시지를 각 세션이 요청한 코드에 맞춰서만 전달한다(서버가 코드별로 나눠줌).
- **캐시 스냅샷(`lastTickerByCode`)**: 코드별 최신 시세 1건을 메모리에 보관. 새 세션 접속 시 즉시 최신값을 보내 빈 화면을 막는다.

## 구조 / 흐름 (확인된 코드 기준)

파일 위치: `springboot/src/main/java/com/chs/springboot/domain/upbit/`

### 상위 스트림 — `UpbitStreamService`
- `@PostConstruct connect()`에서 `BinanceWebSocketStream`(공용 스트림 팩토리)으로 Upbit URL에 연결. 재연결 지연 3초.
- 연결되면 `buildSubscribePayload()`가 만든 구독 메시지 전송. 페이로드는 ticket + `{"type":"ticker","codes":[...5종...]}`.
- `onMessage(json)`: 수신 시 `feedHealthRegistry.markReceived(UPBIT)`로 피드 상태 기록 후, 연결된 클라이언트 세션이 있을 때만 `handler.broadcastPrice(json)` 호출.
- `onError` 시 `NotificationService.sendAlert(...)`로 경보. `@PreDestroy`에서 연결 종료.

### 클라이언트 세션 관리 — `UpbitPriceWebSocketHandler` (`TextWebSocketHandler`)
- `afterConnectionEstablished`: 세션을 `ConcurrentWebSocketSessionDecorator`(전송 타임아웃 5초, 메시지 64KB 제한)로 감싸 `sessions`에 저장. `parseRequestedCodes(session)`로 `?codes=` 파싱해 `sessionCodes`에 저장. 이어서 `sendCachedSnapshots`로 캐시 최신값 즉시 전송.
- `parseRequestedCodes`: `codes=` 쿼리를 URL 디코딩 → 콤마 분리 → 대문자 정규화 → `unmodifiableSet`. 없으면 빈 집합(= 전체 수신).
- `broadcastPrice(json)`: `extractCode(json)`로 `code` 추출 → `lastTickerByCode` 갱신 → 모든 세션 순회. 세션이 닫혔으면 제거, 세션의 요청 코드가 비어있지 않은데 현재 코드를 포함 안 하면 건너뜀, 아니면 전송.
- `sendCachedSnapshots`: 요청 코드가 있으면 그 코드들의 캐시만, 없으면 `lastTickerByCode` 전체를 보낸다.
- 종료/에러(`afterConnectionClosed`, `handleTransportError`)와 전송 실패 시 `sessions`/`sessionCodes`에서 해당 세션 제거.

### 주의: `UpbitSubscriptionChangeEvent` 는 현재 미연결(dead code)
- `UpbitSubscriptionChangeEvent`(ApplicationEvent) 클래스는 존재하지만, **코드베이스 어디에서도 발행(`publishEvent`)하거나 수신(`@EventListener`)하지 않는다**(2026-06-24 grep 확인, 자기 파일 외 참조 0건).
- 따라서 "세션 구독 합집합이 바뀌면 상위 구독을 동적으로 갱신한다"는 흐름은 **현재 동작하지 않는다**. 실제 동작은 위의 *고정 전체 구독*이다. (클래스 헤더 주석은 의도를 적어둔 것이며 실제 배선과 불일치 — index.md lint 참고.)

## 연관 도메인
- 시세 화면: `fe-page-binance`(Binance·Upbit 티커 비교). 공용 스트림 인프라는 `be-binance`의 `BinanceWebSocketStream`을 재사용. 상세 관계는 `index.md`.
