// [AGENT] 크리티컬 헬스 체크 카탈로그 — 마스터 체크리스트(33개)를 코드로 고정
// 세부 계측은 이후 단계에서 채운다(뼈대 우선). 문서: docs/health-check-board.md
// agentRunner(Codex runner) 체크는 제외 — lab(home) 기준.
package com.chs.springboot.global.monitor.health;

import java.util.List;

public enum HealthCheckCatalog {

    // ── L1 인프라 연결 ────────────────────────────────────────────────
    INFRA_MYSQL("infra-mysql", HealthLayer.L1_INFRA, HealthPriority.CRITICAL, "MySQL 연결·응답", "주 DB(MySQL) 연결·응답 여부"),
    INFRA_REDIS("infra-redis", HealthLayer.L1_INFRA, HealthPriority.CRITICAL, "Redis 연결·응답", "Redis 연결·응답 여부(리더선출·캐시·큐 기반)"),
    INFRA_KAFKA("infra-kafka", HealthLayer.L1_INFRA, HealthPriority.CRITICAL, "Kafka 브로커 연결", "Kafka 브로커 연결 여부(파이프라인 버스)"),
    INFRA_POSTGRES("infra-postgres", HealthLayer.L1_INFRA, HealthPriority.CRITICAL, "Postgres(pgvector) 연결", "챗봇 RAG용 Postgres(pgvector) 연결 여부"),

    // ── L2 데이터 유입(피드) ──────────────────────────────────────────
    FEED_BINANCE_TICKER("feed-binance-ticker", HealthLayer.L2_FEED, HealthPriority.CRITICAL, "binance-ticker 신선도", "바이낸스 ticker 피드가 실제로 수신되는지(신선도)"),
    FEED_BINANCE_AGGTRADE("feed-binance-aggtrade", HealthLayer.L2_FEED, HealthPriority.CRITICAL, "binance-aggTrade 신선도", "바이낸스 aggTrade 피드 신선도(차트 데이터 원천)"),
    FEED_UPBIT("feed-upbit", HealthLayer.L2_FEED, HealthPriority.HIGH, "upbit 신선도", "업비트 피드가 실제로 수신되는지(신선도)"),
    FEED_WS_RECONNECT("feed-ws-reconnect", HealthLayer.L2_FEED, HealthPriority.HIGH, "WS 재연결 루프 상태", "WebSocket 재연결 루프가 정상 동작하는지"),

    // ── L3 파이프라인 처리 ────────────────────────────────────────────
    PIPE_KAFKA_CONSUMER("pipe-kafka-consumer", HealthLayer.L3_PIPELINE, HealthPriority.CRITICAL, "rawwriter 소비지연/정지", "rawwriter 컨슈머가 밀리지 않고 소비 중인지"),
    PIPE_AGGTRADE_FLUSH("pipe-aggtrade-flush", HealthLayer.L3_PIPELINE, HealthPriority.CRITICAL, "aggTrade flush(1s) 적체", "aggTrade 큐→DB 플러시(1초)가 적체 없이 도는지"),
    PIPE_ROLLUP_1S("pipe-rollup-1s", HealthLayer.L3_PIPELINE, HealthPriority.CRITICAL, "1초 롤업 최근 성공", "1초봉 롤업이 최근 정상 수행됐는지"),
    PIPE_ROLLUP_1M("pipe-rollup-1m", HealthLayer.L3_PIPELINE, HealthPriority.CRITICAL, "1분 롤업 최근 성공", "1분봉 롤업이 최근 정상 수행됐는지"),
    PIPE_ROLLUP_5M("pipe-rollup-5m", HealthLayer.L3_PIPELINE, HealthPriority.HIGH, "5분 롤업 최근 성공", "5분봉 롤업이 최근 정상 수행됐는지"),
    PIPE_EMPTY_CANDLE_FIX("pipe-empty-candle-fix", HealthLayer.L3_PIPELINE, HealthPriority.HIGH, "빈캔들 교정(5분) 성공", "WS 공백 구간 빈 캔들 교정(5분)이 최근 수행됐는지"),
    PIPE_S3_ARCHIVE("pipe-s3-archive", HealthLayer.L3_PIPELINE, HealthPriority.HIGH, "raw→S3 아카이브(10분) 성공", "raw→S3 아카이브(10분)가 최근 성공했는지"),

    // ── L4 데이터 무결성 ──────────────────────────────────────────────
    DATA_CANDLE_GAP("data-candle-gap", HealthLayer.L4_DATA, HealthPriority.HIGH, "캔들 gap 없음", "캔들 데이터에 누락 구간(gap)이 없는지"),
    DATA_QUALITY("data-quality", HealthLayer.L4_DATA, HealthPriority.HIGH, "데이터 품질", "데이터 품질(이상치·플랫 등)이 정상인지"),

    // ── L5 리더/스케줄러 ──────────────────────────────────────────────
    SCHED_LEADER_ELECTION("sched-leader-election", HealthLayer.L5_SCHEDULER, HealthPriority.CRITICAL, "Redis 리더 선출(5s)", "Redis 리더 선출 하트비트가 정상인지"),
    SCHED_WEATHER("sched-weather", HealthLayer.L5_SCHEDULER, HealthPriority.HIGH, "weather(cron 10분) 최근 성공", "날씨 수집 스케줄러(10분)가 최근 성공했는지"),
    SCHED_NEWS("sched-news", HealthLayer.L5_SCHEDULER, HealthPriority.HIGH, "news RSS(5분) 최근 성공", "뉴스 RSS 수집(5분)이 최근 성공했는지"),
    SCHED_TELEGRAM_POLL("sched-telegram-poll", HealthLayer.L5_SCHEDULER, HealthPriority.HIGH, "telegram 폴링(30s) 최근 성공", "텔레그램 폴링(30초)이 최근 성공했는지"),
    SCHED_OPENINTEREST_POLL("sched-openinterest-poll", HealthLayer.L5_SCHEDULER, HealthPriority.HIGH, "OpenInterest 폴링(60s) 성공", "미결제약정 폴링(60초)이 최근 성공했는지"),
    SCHED_ANALYSIS("sched-analysis", HealthLayer.L5_SCHEDULER, HealthPriority.HIGH, "analysis 탐지(60s) 성공", "분석 탐지 스케줄러(60초)가 최근 성공했는지"),

    // ── L6 외부 연동 ──────────────────────────────────────────────────
    EXT_TELEGRAM_SEND("ext-telegram-send", HealthLayer.L6_EXTERNAL, HealthPriority.HIGH, "Telegram 송신 성공/실패", "텔레그램 메시지 송신 성공 여부"),
    EXT_LLM("ext-llm", HealthLayer.L6_EXTERNAL, HealthPriority.HIGH, "LLM 채팅·임베딩 응답", "LLM 채팅·임베딩 응답 정상 여부"),
    EXT_WEATHER_API("ext-weather-api", HealthLayer.L6_EXTERNAL, HealthPriority.LOW, "Weather API 호출", "외부 날씨 API 호출 정상 여부"),
    EXT_NEWS_RSS("ext-news-rss", HealthLayer.L6_EXTERNAL, HealthPriority.LOW, "News 원본 RSS 응답", "뉴스 원본 RSS 응답 정상 여부"),
    EXT_SECURITY_SCAN("ext-security-scan", HealthLayer.L6_EXTERNAL, HealthPriority.LOW, "VirusTotal / SafeBrowsing", "업로드 보안 검사(VirusTotal/SafeBrowsing) 정상 여부"),

    // ── L7 리소스/용량 ────────────────────────────────────────────────
    RES_CPU("res-cpu", HealthLayer.L7_RESOURCE, HealthPriority.HIGH, "CPU 임계", "CPU 사용률 임계 초과 여부"),
    RES_RAM("res-ram", HealthLayer.L7_RESOURCE, HealthPriority.HIGH, "RAM 임계", "메모리 사용률 임계 초과 여부"),
    RES_DISK("res-disk", HealthLayer.L7_RESOURCE, HealthPriority.HIGH, "DISK 임계", "디스크 사용률 임계 초과 여부"),
    RES_RAWTABLE_GROWTH("res-rawtable-growth", HealthLayer.L7_RESOURCE, HealthPriority.HIGH, "raw_agg_trade 테이블 폭증", "raw_agg_trade 테이블이 비정상 폭증하는지"),
    RES_WS_CONNECTIONS("res-ws-connections", HealthLayer.L7_RESOURCE, HealthPriority.LOW, "WS 연결수 이상", "WebSocket 연결 수가 비정상인지");

    private final String key;
    private final HealthLayer layer;
    private final HealthPriority priority;
    private final String label;
    private final String description;

    HealthCheckCatalog(String key, HealthLayer layer, HealthPriority priority, String label, String description) {
        this.key = key;
        this.layer = layer;
        this.priority = priority;
        this.label = label;
        this.description = description;
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
