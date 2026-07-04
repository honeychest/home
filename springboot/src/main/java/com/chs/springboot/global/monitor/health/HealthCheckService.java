// [AGENT] 헬스 체크 보드 집계 서비스
// 카탈로그 33개를 전부 나열하고, 각 체크의 상태는 카탈로그가 선언한 소스(HealthSource)로 판정한다.
// 팝오버 강화: 설명·판정근거·체크별 최근 실패 이력(3건) 포함.
package com.chs.springboot.global.monitor.health;

import com.chs.springboot.global.monitor.feed.FeedHealthRegistry;
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

    // 임계 문구는 StatusLadder 사다리 상수에서 파생 — 판정값과 문구가 어긋날 수 없다
    private static final String FEED_THRESHOLD_TEXT = StatusLadder.FEED_SECONDS.text("초");
    private static final String RES_THRESHOLD_TEXT = StatusLadder.RESOURCE_PCT.text("%", "(AlertService 임계와 동일)");
    private static final String RAWTABLE_THRESHOLD_TEXT = StatusLadder.RAWTABLE_GB.text("GB", " (data+index)");
    private static final String WSCONN_THRESHOLD_TEXT = StatusLadder.WS_CONNS.text("", " (4개 핸들러 합)");

    // 인프라(infra-*) 판정 기준: InfraHealthEvaluator(20초 주기, 리더)가 적립한 이벤트 기반(경고 단계 없음, UP 아니면 DOWN).
    // 보드 요청 경로에서 실접속 프로브를 제거 — 대상 장애 시 접속 시간초과로 보드 로딩이 지연되는 것을 방지.
    private static final String INFRA_THRESHOLD_TEXT = "20초 주기 능동 프로브 기록 기반 — UP 아니면 DOWN";

    private static final double GB = 1024d * 1024 * 1024;

    private final FeedHealthRegistry feedHealthRegistry;
    private final HealthHeartbeat healthHeartbeat;
    private final HealthCheckEventRepository eventRepository;
    private final MetricCollectorService metricCollectorService;

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
        HealthStatus status = fh == null ? HealthStatus.UNKNOWN : fh.status();
        return new Judgement(status, describeFeed(fh), FEED_THRESHOLD_TEXT);
    }

    private Judgement judgeHeartbeat(String key) {
        HealthHeartbeat.Beat beat = healthHeartbeat.evaluate(key);
        return new Judgement(beat.status(), describeBeat(beat), null);
    }

    private Judgement judgeResource(String key) {
        Double value = resourceValue(key);
        HealthStatus status = value == null ? HealthStatus.UNKNOWN : StatusLadder.RESOURCE_PCT.judge(value);
        return new Judgement(status, describeResource(value), RES_THRESHOLD_TEXT);
    }

    private Judgement judgeRawTable() {
        long bytes = metricCollectorService.getLastRawAggTradeBytes();
        return new Judgement(StatusLadder.RAWTABLE_GB.judgeOrUnknown(bytes / GB),
                describeRawTable(bytes), RAWTABLE_THRESHOLD_TEXT);
    }

    private Judgement judgeWsConn() {
        int conns = metricCollectorService.getLastWsConnections();
        return new Judgement(StatusLadder.WS_CONNS.judgeOrUnknown(conns),
                describeWsConn(conns), WSCONN_THRESHOLD_TEXT);
    }

    private Judgement judgeInfra(String key) {
        return judgeFromEvents(key, INFRA_THRESHOLD_TEXT);
    }

    private Judgement judgeEvent(String key) {
        return judgeFromEvents(key, null);
    }

    // 평가기·호출지점 push 가 이벤트로 적립한 결과를 읽는다.
    // 미복구(open) 이벤트 있으면 그 상태, 없으면 정상(UP). "알려진 실패 없음" 낙관 표시.
    private Judgement judgeFromEvents(String key, String thresholdText) {
        HealthCheckEvent open =
                eventRepository.findTopByCheckKeyAndResolvedAtIsNullOrderByLastFailedAtDesc(key);
        if (open == null) {
            return new Judgement(HealthStatus.UP, "정상", thresholdText);
        }
        HealthStatus status = "DEGRADED".equals(open.getStatus()) ? HealthStatus.DEGRADED : HealthStatus.DOWN;
        return new Judgement(status, open.getCause() != null ? open.getCause() : "이상 감지", thresholdText);
    }

    // ── 소스별 세부 계산 ────────────────────────────────────────────

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

    private static String describeResource(Double value) {
        if (value == null) return "수집 기록 없음";
        return "%.1f%%".formatted(value);
    }

    // ── rawtable/ws 공용 판정근거 문구 — 표시와 이력 적립(ResourceHealthEvaluator)이 공유 ──

    public static String describeRawTable(long bytes) {
        if (bytes < 0) return "수집 기록 없음";
        return "raw_agg_trade %.1fGB".formatted(bytes / (1024d * 1024d * 1024d));
    }

    public static String describeWsConn(int conns) {
        if (conns < 0) return "수집 기록 없음";
        return "WS 세션 " + conns + "개";
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
