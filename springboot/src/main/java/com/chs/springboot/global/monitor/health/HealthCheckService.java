// [AGENT] 헬스 체크 보드 집계 서비스
// 카탈로그 33개를 전부 나열하고, 신호가 있는 체크(피드)는 실연동, 나머지는 UNKNOWN(미구현) 표시.
// 팝오버 강화: 설명·판정근거·체크별 최근 실패 이력(3건) 포함.
package com.chs.springboot.global.monitor.health;

import com.chs.springboot.global.monitor.feed.FeedHealthConfig;
import com.chs.springboot.global.monitor.feed.FeedHealthRegistry;
import com.chs.springboot.global.monitor.feed.FeedStatus;
import com.chs.springboot.global.monitor.service.MetricCollectorService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class HealthCheckService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // 카탈로그 key → FeedHealthRegistry feedId 매핑 (신호가 이미 있는 체크)
    private static final Map<String, String> FEED_KEY_MAP = Map.of(
            HealthCheckCatalog.FEED_BINANCE_TICKER.key(), FeedHealthConfig.BINANCE_TICKER,
            HealthCheckCatalog.FEED_BINANCE_AGGTRADE.key(), FeedHealthConfig.BINANCE_AGG_TRADE,
            HealthCheckCatalog.FEED_UPBIT.key(), FeedHealthConfig.UPBIT
    );

    // 피드 판정 기준 (FeedHealthConfig 등록값과 동일: staleSeconds=10, downSeconds=30)
    private static final String FEED_THRESHOLD_TEXT = "경고 ≥10초 · 다운 ≥30초";

    // 리소스(res-*) 판정 기준: 다운 임계는 AlertService의 기존 CPU/RAM/DISK 80% CRITICAL 라인과 동일값 재사용.
    // 경고(70%)는 다운 전 조기 경보용 여유값.
    private static final double RES_DEGRADED_PCT = 70d;
    private static final double RES_DOWN_PCT = 80d;
    private static final String RES_THRESHOLD_TEXT = "경고 ≥70% · 다운 ≥80%(AlertService 임계와 동일)";

    // 인프라(res-*) 판정 기준: Actuator HealthIndicator / AdminClient 응답 그대로(경고 단계 없음, UP 아니면 DOWN)
    private static final String INFRA_THRESHOLD_TEXT = "능동 프로브(연결 확인) — UP 아니면 DOWN";

    // rawtable/ws 판정 기준: 퍼센트가 아닌 절대값 임계. 상수 1곳에서 관리(추후 튜닝 용이).
    // 표시(HealthCheckService)와 이력 적립(ResourceHealthEvaluator)이 아래 static 판정을 공유 → 단일 소스.
    private static final long RAWTABLE_DEGRADED_BYTES = 3L * 1024 * 1024 * 1024; // 3GB
    private static final long RAWTABLE_DOWN_BYTES = 6L * 1024 * 1024 * 1024;     // 6GB
    private static final String RAWTABLE_THRESHOLD_TEXT = "경고 ≥3GB · 다운 ≥6GB (data+index)";
    private static final int WSCONN_DEGRADED = 300;
    private static final int WSCONN_DOWN = 800;
    private static final String WSCONN_THRESHOLD_TEXT = "경고 ≥300 · 다운 ≥800 (4개 핸들러 합)";

    private final FeedHealthRegistry feedHealthRegistry;
    private final HealthHeartbeat healthHeartbeat;
    private final HealthCheckEventRepository eventRepository;
    private final MetricCollectorService metricCollectorService;
    private final InfraHealthProbe infraHealthProbe;

    /** 보드에 표시할 전체 체크 목록 */
    public List<HealthCheckView> getChecks() {
        Map<String, FeedHealthRegistry.FeedHealth> feeds = new HashMap<>();
        for (FeedHealthRegistry.FeedHealth f : feedHealthRegistry.snapshot()) {
            feeds.put(f.feedId(), f);
        }

        List<HealthCheckView> out = new ArrayList<>(HealthCheckCatalog.all().size());
        for (HealthCheckCatalog c : HealthCheckCatalog.all()) {
            HealthStatus status;
            String detail;
            String thresholdText;

            String feedId = FEED_KEY_MAP.get(c.key());
            if (feedId != null) {
                FeedHealthRegistry.FeedHealth fh = feeds.get(feedId);
                status = mapFeedStatus(fh);
                detail = describeFeed(fh);
                thresholdText = FEED_THRESHOLD_TEXT;
            } else if (healthHeartbeat.isRegistered(c.key())) {
                HealthHeartbeat.Beat beat = healthHeartbeat.evaluate(c.key());
                status = beat.status();
                detail = describeBeat(beat);
                thresholdText = null;
            } else if (isResourceKey(c.key())) {
                Double value = resourceValue(c.key());
                status = mapResourceStatus(value);
                detail = describeResource(value);
                thresholdText = RES_THRESHOLD_TEXT;
            } else if (isInfraKey(c.key())) {
                InfraHealthProbe.Probe probe = infraProbe(c.key());
                status = probe.status();
                detail = probe.detail();
                thresholdText = INFRA_THRESHOLD_TEXT;
            } else if (c.key().equals(HealthCheckCatalog.RES_RAWTABLE_GROWTH.key())) {
                long bytes = metricCollectorService.getLastRawAggTradeBytes();
                status = rawTableStatus(bytes);
                detail = describeRawTable(bytes);
                thresholdText = RAWTABLE_THRESHOLD_TEXT;
            } else if (c.key().equals(HealthCheckCatalog.RES_WS_CONNECTIONS.key())) {
                int conns = metricCollectorService.getLastWsConnections();
                status = wsConnStatus(conns);
                detail = describeWsConn(conns);
                thresholdText = WSCONN_THRESHOLD_TEXT;
            } else if (isEventDerivedKey(c.key())) {
                // 능동 평가기(예: DataIntegrityEvaluator)가 이벤트로 적립한 결과를 읽는다.
                // 미복구(open) 이벤트 있으면 그 상태, 없으면 정상(UP). "알려진 실패 없음" 낙관 표시.
                HealthCheckEvent open =
                        eventRepository.findTopByCheckKeyAndResolvedAtIsNullOrderByLastFailedAtDesc(c.key());
                if (open != null) {
                    status = "DEGRADED".equals(open.getStatus()) ? HealthStatus.DEGRADED : HealthStatus.DOWN;
                    detail = open.getCause() != null ? open.getCause() : "이상 감지";
                } else {
                    status = HealthStatus.UP;
                    detail = "정상";
                }
                thresholdText = null;
            } else {
                status = HealthStatus.UNKNOWN;
                detail = "미구현 — 추후 계측";
                thresholdText = null;
            }

            List<HealthCheckView.Failure> recent = new ArrayList<>();
            for (HealthCheckEvent e : eventRepository.findTop3ByCheckKeyOrderByLastFailedAtDesc(c.key())) {
                recent.add(new HealthCheckView.Failure(
                        e.getLastFailedAt() != null ? e.getLastFailedAt().format(TS) : null,
                        e.getStatus(),
                        e.getCause(),
                        e.getResolvedAt() != null ? e.getResolvedAt().format(TS) : null
                ));
            }
            String lastFailedAt = recent.isEmpty() ? null : recent.get(0).at();
            String lastCause = recent.isEmpty() ? null : recent.get(0).cause();

            out.add(new HealthCheckView(
                    c.key(), c.label(), c.description(),
                    c.layer().label(), c.layer().name(), c.priority().label(),
                    status, detail, thresholdText,
                    lastFailedAt, lastCause, recent
            ));
        }
        return out;
    }

    /** 최근 실패 이력 (최신순 100건) */
    public List<HealthCheckEvent> getRecentEvents() {
        return eventRepository.findTop100ByOrderByLastFailedAtDesc();
    }

    private static HealthStatus mapFeedStatus(FeedHealthRegistry.FeedHealth fh) {
        if (fh == null) {
            return HealthStatus.UNKNOWN;
        }
        FeedStatus s = fh.status();
        if (s == FeedStatus.DOWN) {
            return HealthStatus.DOWN;
        }
        if (s == FeedStatus.STALE) {
            return HealthStatus.DEGRADED;
        }
        return HealthStatus.UP;
    }

    private static String describeFeed(FeedHealthRegistry.FeedHealth fh) {
        if (fh == null || fh.secondsSinceLastMessage() == null) {
            return "수신 기록 없음";
        }
        return fh.secondsSinceLastMessage() + "초 전 수신 (누적 " + fh.receivedCount() + ")";
    }

    private static boolean isResourceKey(String key) {
        return key.equals(HealthCheckCatalog.RES_CPU.key())
                || key.equals(HealthCheckCatalog.RES_RAM.key())
                || key.equals(HealthCheckCatalog.RES_DISK.key());
    }

    private Double resourceValue(String key) {
        double v;
        if (key.equals(HealthCheckCatalog.RES_CPU.key())) {
            v = metricCollectorService.getLastCpu();
        } else if (key.equals(HealthCheckCatalog.RES_RAM.key())) {
            v = metricCollectorService.getLastRam();
        } else {
            v = metricCollectorService.getLastDisk();
        }
        return v < 0 ? null : v;
    }

    private static HealthStatus mapResourceStatus(Double value) {
        if (value == null) return HealthStatus.UNKNOWN;
        if (value >= RES_DOWN_PCT) return HealthStatus.DOWN;
        if (value >= RES_DEGRADED_PCT) return HealthStatus.DEGRADED;
        return HealthStatus.UP;
    }

    private static String describeResource(Double value) {
        if (value == null) return "수집 기록 없음";
        return "%.1f%%".formatted(value);
    }

    // ── rawtable/ws 공용 판정 — 표시와 이력 적립(ResourceHealthEvaluator)이 공유 ──

    /** raw_agg_trade 물리크기(바이트) → 상태. -1(미수집)=UNKNOWN. */
    public static HealthStatus rawTableStatus(long bytes) {
        if (bytes < 0) return HealthStatus.UNKNOWN;
        if (bytes >= RAWTABLE_DOWN_BYTES) return HealthStatus.DOWN;
        if (bytes >= RAWTABLE_DEGRADED_BYTES) return HealthStatus.DEGRADED;
        return HealthStatus.UP;
    }

    public static String describeRawTable(long bytes) {
        if (bytes < 0) return "수집 기록 없음";
        return "raw_agg_trade %.1fGB".formatted(bytes / (1024d * 1024d * 1024d));
    }

    /** WS 세션 합계 → 상태. -1(미수집)=UNKNOWN. */
    public static HealthStatus wsConnStatus(int conns) {
        if (conns < 0) return HealthStatus.UNKNOWN;
        if (conns >= WSCONN_DOWN) return HealthStatus.DOWN;
        if (conns >= WSCONN_DEGRADED) return HealthStatus.DEGRADED;
        return HealthStatus.UP;
    }

    public static String describeWsConn(int conns) {
        if (conns < 0) return "수집 기록 없음";
        return "WS 세션 " + conns + "개";
    }

    // 능동 평가기·호출지점 push 로 이벤트를 적립하는 체크(표시는 open 이벤트 유무로 판정).
    // L4 무결성 2종 + L2 WS재연결 1종 + L6 외부연동 5종. open 이벤트 없으면 "알려진 실패 없음"으로 정상(UP) 표시.
    private static boolean isEventDerivedKey(String key) {
        return key.equals(HealthCheckCatalog.DATA_CANDLE_GAP.key())
                || key.equals(HealthCheckCatalog.DATA_QUALITY.key())
                || key.equals(HealthCheckCatalog.FEED_WS_RECONNECT.key())
                || key.equals(HealthCheckCatalog.EXT_TELEGRAM_SEND.key())
                || key.equals(HealthCheckCatalog.EXT_LLM.key())
                || key.equals(HealthCheckCatalog.EXT_WEATHER_API.key())
                || key.equals(HealthCheckCatalog.EXT_NEWS_RSS.key())
                || key.equals(HealthCheckCatalog.EXT_SECURITY_SCAN.key());
    }

    private static boolean isInfraKey(String key) {
        return key.equals(HealthCheckCatalog.INFRA_MYSQL.key())
                || key.equals(HealthCheckCatalog.INFRA_REDIS.key())
                || key.equals(HealthCheckCatalog.INFRA_KAFKA.key())
                || key.equals(HealthCheckCatalog.INFRA_POSTGRES.key());
    }

    private InfraHealthProbe.Probe infraProbe(String key) {
        if (key.equals(HealthCheckCatalog.INFRA_MYSQL.key())) return infraHealthProbe.mysql();
        if (key.equals(HealthCheckCatalog.INFRA_REDIS.key())) return infraHealthProbe.redis();
        if (key.equals(HealthCheckCatalog.INFRA_KAFKA.key())) return infraHealthProbe.kafka();
        return infraHealthProbe.postgres();
    }

    private static String describeBeat(HealthHeartbeat.Beat beat) {
        if (beat.status() == HealthStatus.UNKNOWN) {
            return "대기 — 아직 실행 기록 없음";
        }
        if (beat.secondsSinceBeat() == null) {
            return "최근 실행 실패";
        }
        return beat.secondsSinceBeat() + "초 전 마지막 성공";
    }
}
