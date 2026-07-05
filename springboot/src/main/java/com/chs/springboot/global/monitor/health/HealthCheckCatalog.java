// [AGENT] 크리티컬 헬스 체크 카탈로그 — 마스터 체크리스트(34개)를 코드로 고정
// 각 항목은 자기 상태 소스(HealthSource)와 판정 기준(임계 문구·HEARTBEAT 임계 초)을 선언한다.
// 새 체크 추가 = 여기 한 줄(HEARTBEAT 는 경고/다운 초 포함 — 대상 주기의 약 2.5×/5× grace).
// agentRunner(Codex runner) 체크는 제외 — lab(home) 기준. 문서: docs/health-check-board.md
package com.chs.springboot.global.monitor.health;

import com.chs.springboot.global.monitor.feed.FeedHealthConfig;

import java.util.List;

public enum HealthCheckCatalog {

    // ── L1 인프라 연결 ────────────────────────────────────────────────
    INFRA_MYSQL("infra-mysql", HealthLayer.L1_INFRA, HealthPriority.CRITICAL, HealthSource.INFRA, "MySQL 연결·응답", "주 DB(MySQL) 연결·응답 여부"),
    INFRA_REDIS("infra-redis", HealthLayer.L1_INFRA, HealthPriority.CRITICAL, HealthSource.INFRA, "Redis 연결·응답", "Redis 연결·응답 여부(리더선출·캐시·큐 기반)"),
    INFRA_KAFKA("infra-kafka", HealthLayer.L1_INFRA, HealthPriority.CRITICAL, HealthSource.INFRA, "Kafka 브로커 연결", "Kafka 브로커 연결 여부(파이프라인 버스)"),
    INFRA_POSTGRES("infra-postgres", HealthLayer.L1_INFRA, HealthPriority.CRITICAL, HealthSource.INFRA, "Postgres(pgvector) 연결", "챗봇 RAG용 Postgres(pgvector) 연결 여부"),

    // ── L2 데이터 유입(피드) ──────────────────────────────────────────
    FEED_BINANCE_TICKER("feed-binance-ticker", HealthLayer.L2_FEED, HealthPriority.CRITICAL, HealthSource.FEED, FeedHealthConfig.BINANCE_TICKER, "binance-ticker 신선도", "바이낸스 ticker 피드가 실제로 수신되는지(신선도)"),
    FEED_BINANCE_AGGTRADE("feed-binance-aggtrade", HealthLayer.L2_FEED, HealthPriority.CRITICAL, HealthSource.FEED, FeedHealthConfig.BINANCE_AGG_TRADE, "binance-aggTrade 신선도", "바이낸스 aggTrade 피드 신선도(차트 데이터 원천)"),
    FEED_UPBIT("feed-upbit", HealthLayer.L2_FEED, HealthPriority.HIGH, HealthSource.FEED, FeedHealthConfig.UPBIT, "upbit 신선도", "업비트 피드가 실제로 수신되는지(신선도)"),
    FEED_WS_RECONNECT("feed-ws-reconnect", HealthLayer.L2_FEED, HealthPriority.HIGH, HealthSource.EVENT, "WS 재연결 루프 상태", "WebSocket 재연결 루프가 정상 동작하는지"),

    // ── L3 파이프라인 처리 ────────────────────────────────────────────
    PIPE_KAFKA_CONSUMER("pipe-kafka-consumer", HealthLayer.L3_PIPELINE, HealthPriority.CRITICAL, HealthSource.HEARTBEAT, 60, 180, "rawwriter 소비지연/정지", "rawwriter 컨슈머가 밀리지 않고 소비 중인지"),   // 유입 주기 가변 → 보수적(리더 전용)
    PIPE_AGGTRADE_FLUSH("pipe-aggtrade-flush", HealthLayer.L3_PIPELINE, HealthPriority.CRITICAL, HealthSource.HEARTBEAT, 60, 180, "aggTrade flush(1s) 적체", "aggTrade 큐→DB 플러시(1초)가 적체 없이 도는지"),   // 플러시 주기 가변 → 보수적
    PIPE_ROLLUP_1S("pipe-rollup-1s", HealthLayer.L3_PIPELINE, HealthPriority.CRITICAL, HealthSource.HEARTBEAT, 10, 30, "1초 롤업 최근 성공", "1초봉 롤업이 최근 정상 수행됐는지"),                                // 1초 주기
    PIPE_ROLLUP_1M("pipe-rollup-1m", HealthLayer.L3_PIPELINE, HealthPriority.CRITICAL, HealthSource.HEARTBEAT, 180, 360, "1분 롤업 최근 성공", "1분봉 롤업이 최근 정상 수행됐는지"),                             // 1분 주기
    PIPE_ROLLUP_5M("pipe-rollup-5m", HealthLayer.L3_PIPELINE, HealthPriority.HIGH, HealthSource.HEARTBEAT, 720, 1200, "5분 롤업 최근 성공", "5분봉 롤업이 최근 정상 수행됐는지"),                                // 5분 주기
    PIPE_EMPTY_CANDLE_FIX("pipe-empty-candle-fix", HealthLayer.L3_PIPELINE, HealthPriority.HIGH, HealthSource.HEARTBEAT, 720, 1200, "빈캔들 교정(5분) 성공", "WS 공백 구간 빈 캔들 교정(5분)이 최근 수행됐는지"), // 5분 주기
    PIPE_S3_ARCHIVE("pipe-s3-archive", HealthLayer.L3_PIPELINE, HealthPriority.HIGH, HealthSource.HEARTBEAT, 1500, 2100, "raw→S3 아카이브(10분) 성공", "raw→S3 아카이브(10분)가 최근 성공했는지"),               // 10분 주기

    // ── L4 데이터 무결성 ──────────────────────────────────────────────
    DATA_CANDLE_GAP("data-candle-gap", HealthLayer.L4_DATA, HealthPriority.HIGH, HealthSource.EVENT, "캔들 gap 없음", "캔들 데이터에 누락 구간(gap)이 없는지"),
    DATA_QUALITY("data-quality", HealthLayer.L4_DATA, HealthPriority.HIGH, HealthSource.EVENT, "데이터 품질", "데이터 품질(이상치·플랫 등)이 정상인지"),

    // ── L5 리더/스케줄러 ──────────────────────────────────────────────
    SCHED_LEADER_ELECTION("sched-leader-election", HealthLayer.L5_SCHEDULER, HealthPriority.CRITICAL, HealthSource.HEARTBEAT, 15, 30, "Redis 리더 선출(5s)", "Redis 리더 선출 하트비트가 정상인지"),          // 5초 주기(전 노드 election 루프)
    SCHED_WEATHER("sched-weather", HealthLayer.L5_SCHEDULER, HealthPriority.HIGH, HealthSource.HEARTBEAT, 1500, 2100, "weather(cron 10분) 최근 성공", "날씨 수집 스케줄러(10분)가 최근 성공했는지"),           // 10분 주기
    SCHED_NEWS("sched-news", HealthLayer.L5_SCHEDULER, HealthPriority.HIGH, HealthSource.HEARTBEAT, 720, 1200, "news RSS(5분) 최근 성공", "뉴스 RSS 수집(5분)이 최근 성공했는지"),                             // 5분 주기
    SCHED_TELEGRAM_POLL("sched-telegram-poll", HealthLayer.L5_SCHEDULER, HealthPriority.HIGH, HealthSource.HEARTBEAT, 150, 300, "telegram 폴링(30s) 최근 성공", "텔레그램 폴링(30초)이 최근 성공했는지"),      // 30초 주기
    SCHED_OPENINTEREST_POLL("sched-openinterest-poll", HealthLayer.L5_SCHEDULER, HealthPriority.HIGH, HealthSource.HEARTBEAT, 150, 300, "OpenInterest 폴링(60s) 성공", "미결제약정 폴링(60초)이 최근 성공했는지"), // 60초 주기
    SCHED_ANALYSIS("sched-analysis", HealthLayer.L5_SCHEDULER, HealthPriority.HIGH, HealthSource.HEARTBEAT, 180, 360, "analysis 탐지(60s) 성공", "분석 탐지 스케줄러(60초)가 최근 성공했는지"),                // 60초 주기

    // ── L6 외부 연동 ──────────────────────────────────────────────────
    EXT_TELEGRAM_SEND("ext-telegram-send", HealthLayer.L6_EXTERNAL, HealthPriority.HIGH, HealthSource.EVENT, "Telegram 송신 성공/실패", "텔레그램 메시지 송신 성공 여부"),
    EXT_LLM("ext-llm", HealthLayer.L6_EXTERNAL, HealthPriority.HIGH, HealthSource.EVENT, "LLM 채팅·임베딩 응답", "LLM 채팅·임베딩 응답 정상 여부"),
    EXT_WEATHER_API("ext-weather-api", HealthLayer.L6_EXTERNAL, HealthPriority.LOW, HealthSource.EVENT, "Weather API 호출", "외부 날씨 API 호출 정상 여부"),
    EXT_NEWS_RSS("ext-news-rss", HealthLayer.L6_EXTERNAL, HealthPriority.LOW, HealthSource.EVENT, "News 원본 RSS 응답", "뉴스 원본 RSS 응답 정상 여부"),
    // 보안 검사 2종은 provider별 키 분리 — 기록기는 키당 열린 이벤트 1개 모델이라, 키를 공유하면
    // 한쪽의 정상 기록이 다른 쪽이 연 장애 이벤트를 닫아 상태가 뒤집힌다. (2026-07, 구 ext-security-scan)
    EXT_VIRUSTOTAL("ext-virustotal", HealthLayer.L6_EXTERNAL, HealthPriority.LOW, HealthSource.EVENT, "VirusTotal 파일 검사", "VirusTotal 파일 악성코드 검사 API 정상 여부"),
    EXT_SAFEBROWSING("ext-safebrowsing", HealthLayer.L6_EXTERNAL, HealthPriority.LOW, HealthSource.EVENT, "SafeBrowsing URL 검사", "Google Safe Browsing URL 위협 검사 API 정상 여부"),

    // ── L7 리소스/용량 ────────────────────────────────────────────────
    RES_CPU("res-cpu", HealthLayer.L7_RESOURCE, HealthPriority.HIGH, HealthSource.RESOURCE_PCT, "CPU 임계", "CPU 사용률 임계 초과 여부"),
    RES_RAM("res-ram", HealthLayer.L7_RESOURCE, HealthPriority.HIGH, HealthSource.RESOURCE_PCT, "RAM 임계", "메모리 사용률 임계 초과 여부"),
    RES_DISK("res-disk", HealthLayer.L7_RESOURCE, HealthPriority.HIGH, HealthSource.RESOURCE_PCT, "DISK 임계", "디스크 사용률 임계 초과 여부"),
    RES_RAWTABLE_GROWTH("res-rawtable-growth", HealthLayer.L7_RESOURCE, HealthPriority.HIGH, HealthSource.RAWTABLE, "raw_agg_trade 테이블 폭증", "raw_agg_trade 테이블이 비정상 폭증하는지"),
    RES_WS_CONNECTIONS("res-ws-connections", HealthLayer.L7_RESOURCE, HealthPriority.LOW, HealthSource.WSCONN, "WS 연결수 이상", "WebSocket 연결 수가 비정상인지");

    private final String key;
    private final HealthLayer layer;
    private final HealthPriority priority;
    private final HealthSource source;
    private final String feedId;     // FEED 소스 전용 — FeedHealthRegistry feedId (그 외 null)
    private final HealthHeartbeat.Spec heartbeat; // HEARTBEAT 소스 전용 — 경고/다운 임계 초 (그 외 null)
    private final String label;
    private final String description;

    HealthCheckCatalog(String key, HealthLayer layer, HealthPriority priority, HealthSource source,
                       String label, String description) {
        this(key, layer, priority, source, null, null, label, description);
    }

    HealthCheckCatalog(String key, HealthLayer layer, HealthPriority priority, HealthSource source,
                       String feedId, String label, String description) {
        this(key, layer, priority, source, feedId, null, label, description);
    }

    HealthCheckCatalog(String key, HealthLayer layer, HealthPriority priority, HealthSource source,
                       long staleSeconds, long downSeconds, String label, String description) {
        this(key, layer, priority, source, null, new HealthHeartbeat.Spec(staleSeconds, downSeconds), label, description);
    }

    HealthCheckCatalog(String key, HealthLayer layer, HealthPriority priority, HealthSource source,
                       String feedId, HealthHeartbeat.Spec heartbeat, String label, String description) {
        this.key = key;
        this.layer = layer;
        this.priority = priority;
        this.source = source;
        this.feedId = feedId;
        this.heartbeat = heartbeat;
        this.label = label;
        this.description = description;
    }

    // 소스 ↔ 소스 전용 선언(feedId, 하트비트 임계)의 일치를 클래스 로딩 시점에 강제(fail-fast)
    static {
        for (HealthCheckCatalog c : values()) {
            if ((c.source == HealthSource.FEED) != (c.feedId != null)) {
                throw new IllegalStateException("FEED 소스와 feedId 선언 불일치: " + c.key);
            }
            if ((c.source == HealthSource.HEARTBEAT) != (c.heartbeat != null)) {
                throw new IllegalStateException("HEARTBEAT 소스와 임계 선언 불일치: " + c.key);
            }
        }
    }

    public String key() {
        return key;
    }

    public HealthLayer layer() {
        return layer;
    }

    public HealthPriority priority() {
        return priority;
    }

    public HealthSource source() {
        return source;
    }

    /** FEED 소스 항목의 FeedHealthRegistry feedId. 그 외 소스는 null. */
    public String feedId() {
        return feedId;
    }

    /** HEARTBEAT 소스 항목의 경고/다운 임계 초. 그 외 소스는 null. */
    public HealthHeartbeat.Spec heartbeat() {
        return heartbeat;
    }

    /**
     * 보드 팝오버에 표시할 판정 기준 문구 — 항목이 자기 기준을 스스로 설명한다.
     * 실제 문구 파생은 소스가 소유한다(판정과 문구를 한 소스에 응집). {@link HealthSource#thresholdText}
     */
    public String thresholdText() {
        return source.thresholdText(this);
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public static List<HealthCheckCatalog> all() {
        return List.of(values());
    }
}
