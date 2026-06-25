# 도메인 용어집 (Glossary)

> 챗봇이 "오픈포지션이 뭐야?" 같은 일반 용어 질문에 답할 근거 문서.
> 각 항목은 **일반 정의**와 **이 프로젝트에서의 쓰임**을 함께 적는다.
> wiki-refresh로 소스 검증함(2026-06-24). 도메인별 상세는 `index.md`에서 각 문서로.

---

# 트레이딩 용어

## 오픈포지션 (OI, Open Interest)

**일반적으로** — 아직 청산되지 않고 시장에 열려 있는 선물 계약의 총량. 미결제약정이라고도 한다. OI가 늘면 새 자금이 포지션을 여는 중, 줄면 포지션이 닫히는 중으로 해석한다.

**이 프로젝트에서** — signal 페이지에서 실시간 수집해 면적 차트(OiLineChart)로 보여주며, 런타임 버퍼는 5,000건(`OI_BUFFER_SIZE`). 바이낸스 OI 폴링/백필은 binance 도메인이 담당.

## 청산 (Liquidation, Force Order)

**일반적으로** — 증거금이 부족해진 레버리지 포지션이 거래소에 의해 강제로 시장가 종료되는 것. 롱 청산은 하락 압력, 숏 청산은 상승 압력의 신호로 본다.

**이 프로젝트에서** — 바이낸스 forceOrder 스트림으로 수신해 롱/숏 청산 누계액으로 집계하고, signal 페이지의 청산 패널에 표시한다(버퍼 50건, `LIQUIDATION_BUFFER_SIZE`).

## 펀딩비 (Funding Rate)

**일반적으로** — 무기한 선물 가격을 현물에 붙들어 두기 위해 롱·숏 보유자끼리 주기적으로 주고받는 수수료. 양수면 롱이 숏에게 지불(시장이 롱 과열), 음수면 반대.

**이 프로젝트에서** — signal 페이지 상단 바(TopBar)에서 심볼의 펀딩비 정보를 표시한다.

## 롱 / 숏 (Long / Short)

**일반적으로** — 롱은 가격 상승에 베팅(매수), 숏은 가격 하락에 베팅(매도)하는 포지션.

**이 프로젝트에서** — 매수/매도 에너지, 청산, 패널 구성이 모두 롱/숏 대칭으로 나뉘어 시각화된다.

## 델타 (Delta)

**일반적으로** — 같은 봉(시간 구간) 안에서 공격적 매수 체결량에서 공격적 매도 체결량을 뺀 값. 양수면 매수 우위, 음수면 매도 우위.

**이 프로젝트에서** — analysis 페이지의 조건 중 하나(`DELTA`)로, 캔들에 델타·거래량을 병합해 패턴 탐지에 사용한다. 백엔드는 binance 도메인이 1분/5분봉에 델타를 결합해 제공(`/api/analysis/delta`).

## 다이버전스 (Divergence)

**일반적으로** — 가격과 보조지표(또는 다른 데이터)가 서로 반대 방향으로 움직이는 현상. 추세 약화·반전의 단서로 본다.

**이 프로젝트에서** — signal 페이지의 DivergenceBar로 가격과 에너지/OI 간의 괴리를 시각화한다.

## 거래량 급증 (Volume Spike)

**일반적으로** — 평소보다 거래량이 급격히 늘어난 구간. 변동성·관심 집중의 신호.

**이 프로젝트에서** — analysis/signal의 조건(`VOLUME_SPIKE`)으로, 직전 20개 봉(`REF_BARS`) 평균 거래량 대비 배수 이상인지로 판정한다.

## 인사이드바 (Inside Bar)

**일반적으로** — 현재 봉의 고가·저가가 직전 봉(들)의 고저 범위 안에 완전히 들어오는 캔들. 변동성 수축·잠복의 신호로 본다.

**이 프로젝트에서** — signal 스텔스 패턴 Type A의 조건(`insideBarCondition`): 현재 봉 고가 < 직전 N봉 최고가 AND 저가 > 직전 N봉 최저가.

## 도지 (Doji)

**일반적으로** — 시가와 종가가 거의 같아 몸통이 없는 캔들. 매수·매도 균형(방향성 불확실)을 뜻한다.

**이 프로젝트에서** — 스텔스 Type B는 직전 N봉이 모두 도지(몸통 크기=0)인 경우를 제외한다(`notAllDojisCondition`).

## 캔들 (Candle / Kline)

**일반적으로** — 일정 시간 구간의 시가·고가·저가·종가(OHLC)를 하나의 봉으로 나타낸 차트 단위. "봉 마감"은 그 구간이 끝나 값이 확정되는 것.

**이 프로젝트에서** — WebSocket(`/ws/candle/{interval}`)으로 실시간 캔들을 받아 `is_closed`(봉 마감 여부)에 따라 진행 중 봉 갱신/마감 처리를 한다.

## 체결 집계 (aggTrade, Aggregated Trade)

**일반적으로** — 같은 가격·같은 방향의 연속 체결을 하나로 묶은 거래 데이터. 매수자가 메이커였는지(`isBuyerMaker`)로 공격적 매수/매도를 구분한다.

**이 프로젝트에서** — binance 도메인이 WebSocket/Kafka로 수집해 원시 저장(`raw_agg_trade`)하고, signal 페이지는 이를 받아 매수/매도 에너지를 가감한다.

## 김치 프리미엄 (Premium)

**일반적으로** — 같은 코인이 국내(원화) 거래소에서 해외(달러) 거래소보다 비싸게(또는 싸게) 거래되는 가격차.

**이 프로젝트에서** — binance 페이지가 `업비트 KRW 현재가 − (바이낸스 USDT가 × USDT/KRW 환율)`로 계산해 비율(`premiumRate`)과 함께 티커 카드에 표시한다.

---

# 시스템 · 데이터 파이프라인 용어

## 롤업 (Rollup)

**일반적으로** — 더 작은 시간 단위 데이터를 모아 큰 단위로 집계하는 것.

**이 프로젝트에서** — binance 도메인이 체결을 1초봉 → 1분봉 → 5분봉으로 단계 집계한다(`AggTrade1sRollupService`/`AggTradeRollupService`). 수동 롤업은 admin 페이지에서 실행.

## 백필 (Backfill)

**일반적으로** — 누락되었거나 과거의 데이터를 사후에 채워 넣는 작업.

**이 프로젝트에서** — binance 도메인이 바이낸스 REST(aggTrades/klines)로 빠진 구간을 메운다(`AggTradeBackfillService`/`ManualBackfillService`). admin 페이지의 갭 조회 → 수동 수집이 이 흐름.

## 리더 노드 (Leader / Leader Election)

**일반적으로** — 여러 서버 인스턴스 중 특정 작업을 실제로 수행할 한 대를 정하는 것. 중복 수집·중복 스케줄을 막는다.

**이 프로젝트에서** — `LeaderElectionService.isLeader()`로 판정. 수집 스트림 연결·롤업·analysis 자동 탐지 스케줄러 등은 리더 노드에서만 동작한다.

## SSE (Server-Sent Events)

**일반적으로** — 서버 → 클라이언트 단방향 실시간 푸시(HTTP 기반, `EventSource`).

**이 프로젝트에서** — 체결/틱/시그널 등 빈번한 일방향 데이터에 사용(`/api/binance/trades/sse`, `/api/signal/stream/sse`). 챗봇 답변 SSE는 아니고, 문의 답변 알림(`/api/support/reply/sse`)에도 쓰인다.

## WebSocket

**일반적으로** — 브라우저↔서버 양방향 실시간 통신.

**이 프로젝트에서** — 시세 티커(`/ws/binance-price`, `/ws/upbit-price`), 실시간 캔들(`/ws/candle`), 모니터링(`/ws/monitor`)에 사용. 상위 거래소 연결은 백엔드가 단일 소켓으로 대신 받아 세션별로 중계한다.

## guestToken

**일반적으로** — 로그인 없이 기기를 구분하는 영구 식별자.

**이 프로젝트에서** — support(문의) 기능이 `localStorage` 키 `chs_guest_token`의 UUID로 기기를 식별해, 문의 전송·내역 조회·답변 읽음을 같은 기기로 묶는다.

## routingKey / 이벤트 큐

**일반적으로** — 메시지를 어느 구독자에게 보낼지 가리는 키. `{aggregate}.{verb}.{과거형}` 같은 컨벤션을 쓴다.

**이 프로젝트에서** — logistics 시뮬레이션이 브라우저 내 인메모리 큐(`InMemoryQueue`)로 도메인 간 이벤트를 주고받는다. 실제 발행 형식은 `{prefix}{stage}.{nodeKey}.done`(예 `order.received.raw-ingest.done`).

---

# 운영 · 모니터링 표시 용어

> monitor 페이지(특히 하단 "알림 이력" 표)와 피드 상태 카드에 코드 그대로 표시되는 식별자/상태값의 뜻.
> 사용자가 "FEED_UPBIT가 뭐야?", "지속 1분이 무슨 뜻?", "STALE이 뭐야?" 처럼 화면에 보이는 값을 물을 때 답할 근거.
> 출처: `AlertHistory.MetricType`/`Severity`(엔티티), `FeedStatus`(enum), `AlertService`(임계 로직), `AlertHistoryTable.jsx`(표시).

## 알림 지표 (metric_type)

알림 이력 표 "지표" 칸에 enum 코드로 표시된다. 한글 라벨로도 함께 보여준다.

- **CPU** — 프로세서 사용률(%) 과부하 알림.
- **RAM** — 메모리 사용률(%) 과부하 알림.
- **DISK** — 디스크 사용률(%) 과부하 알림.
- **REDIS_QUEUE** — Redis 큐 적체 알림.
- **API_ERROR** — API 에러율 알림(피드 ID가 매핑 안 되는 경우의 기본값으로도 쓰임).
- **FEED_BINANCE_TICKER** — 바이낸스 시세 피드(`binance-ticker`) 수신 끊김/지연 알림.
- **FEED_BINANCE_AGG** — 바이낸스 체결 피드(`binance-aggTrade`) 수신 끊김/지연 알림.
- **FEED_UPBIT** — 업비트 시세 피드(`upbit`) 수신 끊김/지연 알림.

## 심각도 (severity)

- **WARN** — 경고. 피드가 잠시 지연(STALE)된 수준.
- **CRITICAL** — 긴급. 자원 임계 지속 초과 또는 피드 끊김(DOWN) 수준.

## 피드 상태 (FeedStatus, UP / STALE / DOWN)

업스트림 WebSocket 수신 채널의 freshness(최근 수신 신선도) 판정. monitor 페이지 "피드 상태" 카드의 배지로 표시된다.

- **UP**(🟢) — 정상 수신 중.
- **STALE**(🟡) — 마지막 수신 후 10초 경과(지연). WARN 알림 대상.
- **DOWN**(🔴) — 마지막 수신 후 30초 경과(끊김). CRITICAL 알림 대상.

## 알림 이력 표의 값 (현재값 · 임계값 · 지속)

- **현재값(value)** — 알림 시점 측정값. 자원은 사용률(%), 피드는 마지막 수신 후 경과 초.
- **임계값(threshold)** — 알림 발생 기준. 자원 80%, 피드 STALE 10초 / DOWN 30초.
- **지속(duration)** — 임계 초과가 이어진 시간. 표에서는 `durationSec`을 분으로 올림해 "N분"으로 보여준다. 예) "FEED_UPBIT … 1분"은 업비트 피드 이상이 약 1분간 지속됐다는 뜻.

## 알림 발생·억제 규칙 (AlertService 기준)

- **CPU/RAM** — 80% 이상이 30초(5초×6회) 지속되면 CRITICAL, 같은 지표는 1시간 쿨다운.
- **DISK** — 80% 이상이면 하루 1회만 알림.
- **피드(FEED_*)** — STALE이면 WARN, DOWN이면 CRITICAL. 피드·상태별 1시간 쿨다운.
- **무음(silence)** — Redis `monitor:silence=ON`이면 전체 알림 일시 정지.

---

# 챗봇(RAG) 용어

## RAG (Retrieval-Augmented Generation)

**일반적으로** — 질문과 관련된 문서를 먼저 검색해, 그 내용을 근거로 LLM이 답을 생성하는 방식. 근거 없는 환각을 줄인다.

**이 프로젝트에서** — 코드베이스 챗봇이 PGVector에서 유사 청크를 검색(`EvidenceRetriever`)해 근거로 답한다. 답변은 "근거에 있는 코드 사실만, 일반 개념은 일반지식 허용, 둘 다 없으면 모름" 규칙을 강제.

## layer (doc / code)

**일반적으로** — 색인된 청크의 출처 구분 태그.

**이 프로젝트에서** — `docs/generated/` 하위 자연어 문서면 `doc`, 그 외 실제 소스코드면 `code`. 문서만 다시 색인하는 `/reindex/docs`는 `layer='doc'` 청크만 지웠다 다시 넣는다(소스 벡터 보존).

## 재색인 (Reindex)

**일반적으로** — 검색 색인을 다시 만드는 것.

**이 프로젝트에서** — 3종. 전체(`/reindex`, 소스+문서 전부), 문서만(`/reindex/docs`, docs/generated 증분), 도메인별(`/reindex/domain/{domain}`, 그 도메인 소스만). 위키 문서를 고쳤으면 `/reindex/docs` 하나면 된다.

## 청킹 / SYMBOL_AWARE (Chunking)

**일반적으로** — 문서를 임베딩 단위로 쪼개는 것.

**이 프로젝트에서** — 전략 두 가지. `TOKEN`(토큰 단위 분할, 기본), `SYMBOL_AWARE`(GitNexus 심볼 경계로 메서드/클래스 단위 분할, 실패 시 토큰 폴백). 청크 크기 512토큰·오버랩 64토큰.

## pageId / pageBoost

**일반적으로** — 사용자가 보고 있는 화면 식별자(pageId)와, 그 화면 관련 문서에 검색 점수를 더해주는 가중(pageBoost).

**이 프로젝트에서** — 프론트(FloatingChatbot)가 라우트에서 pageId(예 `signal`)를 함께 보내면, `EvidenceRetriever`가 그 페이지 경로의 청크에 `pageBoost`(0.15)를 더해 재정렬한다(하드필터 아님). pageId↔경로 매핑은 `PageContextRegistry`.
