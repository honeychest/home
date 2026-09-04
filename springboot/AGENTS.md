# springboot 작업 규칙 (모든 AI 에이전트 공통)

이 폴더의 파일을 수정하기 전에 읽는다. 절차 규칙(승인·커밋·점검)은 `/chs/chs-rules.md`.

## 가져다 쓸 것 — 패턴 카탈로그 (같은 일을 하는 코드를 새로 만들면 그게 결함이다)
지시할 때 키 이름으로 부를 수 있다.
모범 실물은 살아있는 프로덕션 코드다 — 별도 예제 코드를 만들지 말고 실물을 열어 모방한다.

| 키 | 의도 (언제 쓰나) | 모범 실물 |
|---|---|---|
| pattern-async-sse-dispatch | SSE(emitter) 팬아웃을 웹소켓 수신 등 호출 스레드에서 떼어내기. emitter.send()를 호출 스레드에서 동기로 돌리면 느린 클라이언트 하나가 그 스레드를 막는다(실측: 거래량 급증 시 재연결 반복). emitter 목록·직렬화·이벤트 이름 같은 서비스별 로직은 그대로 두고, 실행기(단일 데몬 스레드) 생명주기만 공용화 | `binance/service/AsyncSseDispatcher.java` + `binance/service/SignalSseService.java`·`RawTickSseService.java`·`BinanceTradeSseService.java` |

적립 규칙: 이번 작업에서 2곳 이상 쓰일 만한 구조를 만들었으면 커밋 전에 키를 붙여
이 표에 등록한다. 실물이 리팩토링되면 키는 유지하고 경로만 갱신. 10개를 넘으면
`springboot/docs/patterns.md` 로 분리하고 여기엔 링크만 남긴다.
사람용 교보재(흐름 설명·뼈대 코드)는 `frontend/src/page/admin/backendPatterns.js` 원장 —
admin > "백엔드 패턴"에서 열람. 패턴 등록·변경 시 표와 이 원장 두 곳을 함께 갱신한다.

## 금지
- 새 `@Scheduled` 를 중복 실행 고려 없이 추가 (앱 2인스턴스 — 멱등성 또는 단일 실행 장치 필수).
- 특정 증상 하나만을 위한 좁은 단일 목적 컬럼 신설 — 그 증상을
  낳는 원시 신호를 저장하고 판정은 순수 함수로 분리할 것.

## 서버 제약 (상세: `chs/server/**`, commit-check server-profile)
- 앱 2인스턴스 · 힙 512M(SerialGC) · Hikari 풀 4(배치 6) — 큰 인메모리 적재·긴 트랜잭션 주의.
- DDL 은 Flyway 마이그레이션으로만. prod 는 validate — 매핑 불일치 시 기동 실패.

## 도메인별
| 영역 | 따를 것 |
|---|---|
| binance 자동매매 (신규, PoC 단계 — 착수 전 필수) | `docs/binance/CONTEXT.md` — 선물 전용·안전 원칙 4가지·기존 인프라 이력 |

## 검증
- 커밋 전 `./gradlew test`. 한글 JSON body 를 셸 curl 로 보내면 CP949 로 깨짐 —
  UTF-8 파일 + `--data-binary @파일` 로 검증.
