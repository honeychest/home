// [AGENT] 헬스 체크 보드 집계 서비스
// 카탈로그 33개를 전부 나열하고, 각 체크의 상태는 카탈로그가 선언한 소스(HealthSource)로 판정한다.
// 팝오버 강화: 설명·판정근거·체크별 최근 실패 이력(3건) 포함.
package com.chs.springboot.global.monitor.health;

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

    /** 소스별 판정 결과(상태·판정근거·임계 설명) */
    private record Judgement(HealthStatus status, String detail, String thresholdText) { }

    /** 보드에 표시할 전체 체크 목록 */
    public List<HealthCheckView> getChecks() {
        Map<String, FeedHealthRegistry.FeedHealth> feeds = new HashMap<>();
        for (FeedHealthRegistry.FeedHealth f : feedHealthRegistry.snapshot()) {
            feeds.put(f.feedId(), f);
        }

        List<HealthCheckView> out = new ArrayList<>(HealthCheckCatalog.all().size());
        for (HealthCheckCatalog c : HealthCheckCatalog.all()) {
            Judgement j = switch (c.source()) {
                case FEED -> judgeFeed(feeds.get(c.feedId()));
                case HEARTBEAT -> judgeHeartbeat(c.key());
                case RESOURCE_PCT -> judgeResource(c.key());
                case RAWTABLE -> judgeRawTable();
                case WSCONN -> judgeWsConn();
                case INFRA -> judgeInfra(c.key());
                case EVENT -> judgeEvent(c.key());
            };

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
                    j.status(), j.detail(), j.thresholdText(),
                    lastFailedAt, lastCause, recent
            ));
        }
        return out;
    }

    /** 최근 실패 이력 (최신순 100건) */
    public List<HealthCheckEvent> getRecentEvents() {
        return eventRepository.findTop100ByOrderByLastFailedAtDesc();
    }

    // ── 소스별 판정 ─────────────────────────────────────────────────

    private static Judgement judgeFeed(FeedHealthRegistry.FeedHealth fh) {
        return new Judgement(mapFeedStatus(fh), describeFeed(fh), FEED_THRESHOLD_TEXT);
    }

    private Judgement judgeHeartbeat(String key) {
        HealthHeartbeat.Beat beat = healthHeartbeat.evaluate(key);
        return new Judgement(beat.status(), describeBeat(beat), null);
    }

    private Judgement judgeResource(String key) {
        Double value = resourceValue(key);
        return new Judgement(mapResourceStatus(value), describeResource(value), RES_THRESHOLD_TEXT);
    }

    private Judgement judgeRawTable() {
        long bytes = metricCollectorService.getLastRawAggTradeBytes();
        return new Judgement(rawTableStatus(bytes), describeRawTable(bytes), RAWTABLE_THRESHOLD_TEXT);
    }

    private Judgement judgeWsConn() {
        int conns = metricCollectorService.getLastWsConnections();
        return new Judgement(wsConnStatus(conns), describeWsConn(conns), WSCONN_THRESHOLD_TEXT);
    }

    private Judgement judgeInfra(String key) {
        InfraHealthProbe.Probe probe = infraProbe(key);
        return new Judgement(probe.status(), probe.detail(), INFRA_THRESHOLD_TEXT);
    }

    // 능동 평가기(예: DataIntegrityEvaluator)·호출지점 push 가 이벤트로 적립한 결과를 읽는다.
    // 미복구(open) 이벤트 있으면 그 상태, 없으면 정상(UP). "알려진 실패 없음" 낙관 표시.
    private Judgement judgeEvent(String key) {
        HealthCheckEvent open =
                eventRepository.findTopByCheckKeyAndResolvedAtIsNullOrderByLastFailedAtDesc(key);
        if (open == null) {
            return new Judgement(HealthStatus.UP, "정상", null);
        }
        HealthStatus status = "DEGRADED".equals(open.getStatus()) ? HealthStatus.DEGRADED : HealthStatus.DOWN;
        return new Judgement(status, open.getCause() != null ? open.getCause() : "이상 감지", null);
    }

    // ── 소스별 세부 계산 ────────────────────────────────────────────

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
