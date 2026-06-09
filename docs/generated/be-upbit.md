# Upbit 도메인

## 목차
- 개요
- Upbit 데이터 스트림 연결 및 구독 관리
- 클라이언트 세션 연결 및 요청 코드 파싱
- 실시간 시세 데이터 브로드캐스트 및 필터링 로직
- 캐시된 스냅샷 제공 메커니즘
- 세션 상태 관리 및 예외 처리
- 이벤트 기반 구독 변경 구조 (UpbitSubscriptionChangeEvent)

## 개요

Upbit 도메인은 Upbit WebSocket으로부터 실시간 시세 데이터를 수신하여 클라이언트 세션에 전달하는 기능을 제공합니다.

`UpbitStreamService`는 `UPBIT_STREAM_URL`을 통해 Upbit 상위 서버와 연결을 유지하며, `SUBSCRIBED_CODES`에 정의된 코드를 구독합니다. 연결 시 `buildSubscribePayload()`를 통해 생성된 페이로드를 전송하며, 수신된 메시지는 `onMessage` 메서드를 통해 `UpbitPriceWebSocketHandler.broadcastPrice()`로 전달됩니다.

`UpbitPriceWebSocketHandler`는 클라이언트의 WebSocket 세션을 관리합니다. `afterConnectionEstablished` 메서드에서 접속 시 URI 쿼리 파라미터(`codes=`)를 분석하여 `parseRequestedCodes`를 통해 요청된 코드를 추출하고 세션별로 저장합니다. 또한, 접속 시점에 `sendCachedSnapshots`를 호출하여 `lastTickerByCode`에 저장된 최신 시세 정보를 전달합니다.

데이터 브로드캐스트 과정에서 `broadcastPrice`는 수신된 JSON에서 코드를 추출하여 `lastTickerByCode`를 갱신한 후, 각 세션이 요청한 코드(`sessionCodes`)와 일치하는 경우에만 해당 메시지를 클라이언트에 전송합니다.

## Upbit 데이터 스트림 연결 및 구독 관리

Upbit 서버와의 상위 스트림 연결은 `UpbitStreamService.java`에서 담당하며, `@PostConstruct` 단계에서 `streamFactory.create`를 통해 지정된 URL(`wss://api.upbit.com/websocket/v1`)로 연결을 시도합니다. 연결 성공 시 `buildSubscribePayload()` 메서드를 통해 생성된 구독 페이로드를 전송하며, 이 페이로드는 `SUBSCRIBED_CODES`에 정의된 코드를 포함합니다.

클라이언트 세션 관리는 `UpbitPriceWebSocketHandler.java`에서 수행됩니다. 클라이언트가 접속하면 `afterConnectionEstablished` 메서드가 호출되어 세션을 저장하고, URI 쿼리 파라미터(`codes=`)를 분석하여 `parseRequestedCodes` 메서드를 통해 요청된 코드를 추출한 뒤 `sessionCodes`에 저장합니다. 접속 직후에는 `sendCachedSnapshots` 메서드를 통해 `lastTickerByCode`에 저장된 최신 데이터를 클라이언트에게 전송합니다.

데이터 흐름은 `UpbitStreamService.java`의 `onMessage` 메서드에서 시작됩니다. Upbit로부터 메시지를 수신하면 `feedHealthRegistry.markReceived`를 통해 상태를 기록하고, `UpbitPriceWebSocketHandler.java`의 `broadcastPrice` 메서드를 호출합니다. `broadcastPrice`는 수신된 JSON에서 코드를 추출하여 최신 상태를 업데이트한 뒤, 각 세션의 `session코드가`와 비교하여 해당 코드를 요청한 클라이언트에게만 메시지를 브로드캐스트합니다.

## 클라이언트 세션 연결 및 요청 코드 파싱

클라이언트가 WebSocket 연결을 시도하면 `UpbitPriceWebSocketHandler.java`의 `afterConnectionEstablished` 메서드가 호출됩니다. 이때 연결된 세션은 `ConcurrentWebSocketSessionDecorator`로 감싸져 `sessions` 맵에 저장되며, 동시에 `parseRequestedCodes` 메서드를 통해 세션의 URI 쿼리 파라미터에서 구독할 코드를 추출합니다.

`parseRequestedCodes` 메서드는 URI의 `codes=` 파라미터를 찾아 URL 디코딩을 수행한 후, 쉼표(`,`)로 구분된 각 코드를 대문자로 정규화하여 `Set<String>` 형태로 반환합니다. 추출된 코드는 해당 세션의 요청 정보로서 `sessionCodes` 맵에 저장됩니다. 이후 `sendCachedSnapshots` 메서드가 호출되어, 요청된 코드가 비어 있지 않을 경우 해당 코드에 해당하는 최신 티커 데이터(`lastTickerByCode`)를 클라이언트에게 전송합니다.

## 실시간 시세 데이터 브로드캐스트 및 필터링 로직

`UpbitStreamService.java`의 `onMessage` 메서드가 Upbit로부터 수신한 JSON 데이터를 `UpbitPriceWebSocketHandler.java`의 `broadcastPrice` 메서드로 전달하며 브로드캐스트가 시작됩니다.

`UpbitPriceWebSocketHandler.java`의 `broadcastPrice`는 수신된 JSON에서 `extractCode` 메서드를 통해 심볼 코드(code)를 추출하고, 이를 `lastTickerByCode` 맵에 업데이트하여 최신 시세 스냅샷을 유지합니다. 이후 모든 활성 세션을 순회하며 다음 로직에 따라 데이터를 필터링하여 전송합니다:

*   **세션별 필터링**: 각 세션이 연결 시 `parseRequestedCodes`를 통해 설정한 구독 코드(`sessionCodes`)가 존재할 경우, 추출된 코드가 해당 세션의 요청 코드에 포함되어 있는지 확인합니다. 포함되지 않은 경우 메시지를 전송하지 않습니다.
*   **스냅샷 전송**: 클라이언트가 접속하는 시점(`afterConnectionEstablished`)에는 `sendCachedSnapshots`를 호출하여, 해당 세션이 요청한 코드에 해당하는 최신 시세 데이터가 `lastTickerByCode`에 존재할 경우 즉시 전송합니다.

## 캐시된 스냅샷 제공 메커니즘

클라이언트가 WebSocket 연결을 성공적으로 수립하면 `UpbitPriceWebSocketHandler.java`의 `afterConnectionEstablished` 메서드가 호출되며, 이때 `sendCachedSnapshots`를 통해 캐시된 스냅샷이 제공됩니다.

스냅샷 데이터는 `UpbitPriceWebSocketHandler.java` 내의 `lastTickerByCode` 맵에 저장되어 있습니다. `sendCachedSnapshots` 메서드는 클라이언트가 요청한 특정 코드(`requestedCodes`)가 존재할 경우 해당 코드들에 매칭되는 데이터만 `lastTickerByCode`에서 추출하여 전송합니다. 만약 요청된 코드가 비어 있다면 `lastTickerByCode`에 저장된 모든 티커 데이터를 대상으로 스냅샷을 전송합니다.

이 메커니즘은 `UpbitPriceWebSocketHandler.java`의 `broadcastPrice` 메서드가 호출될 때마다 수신된 JSON 데이터를 `lastTickerByCode`에 업데이트함으로써 최신 상태를 유지합니다.

## 세션 상태 관리 및 예외 처리

`UpbitPriceWebSocketHandler`는 `ConcurrentHashMap`을 사용하여 클라이언트 세션(`sessions`)과 각 세션별 요청 코드 집합(`sessionCodes`)을 관리합니다. `afterConnectionEstablished` 메서드 호출 시 세션을 `ConcurrentWebSocketSessionDecorator`로 감싸 5초의 전송 타임아웃과 64KB의 메시지 크기 제한을 적용하여 안전하게 저장합니다.

세션 종료 시에는 `afterConnectionClosed` 또는 `handleTransportError` 메서드를 통해 `sessions`와 `sessionCodes`에서 해당 세션 정보를 제거합니다. `broadcastPrice` 과정 중 세션이 열려있지 않은 경우(`!session.isOpen()`)에도 즉시 해당 세션 정보를 삭제하도록 설계되어 있습니다.

메시지 전송 중 예외가 발생할 경우, `UpbitPriceWebSocketHandler`는 로그를 기록한 후 해당 세션을 관리 목록에서 제거하여 비정상적인 세션이 시스템에 잔류하지 않도록 처리합니다.

## 이벤트 기반 구독 변경 구조 (UpbitSubscriptionChangeEvent)

프론트엔드 세션들의 업비트 구독 코드 합집합이 변경될 때 발행되는 이벤트입니다. `UpbitSubscriptionChangeEvent.java`는 `ApplicationEvent`를 상속받으며, 모든 세션이 요청한 코드들의 합집합을 `unmodifiableSet` 형태로 보유합니다.

이 이벤트는 세션의 연결 및 해제 시마다 발행될 수 있으며, `UpbidSubscriptionChangeEvent.java`에 정의된 `codes`를 통해 변경된 구독 대상 목록을 전달합니다. `UpbitStreamService.java`는 이 이벤트를 수신하여 업비트 상위 소켓(Upstream)의 구독 대상을 단일 연결 내에서 한 번에 갱신하는 역할을 수행합니다. 또한, `UpbitPriceWebSocketHandler.java`는 이 이벤트의 발행 주체로 동작합니다.
