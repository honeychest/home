// [AGENT] 헬스 체크 보드 집계 서비스
// 카탈로그 34개를 전부 나열하고, 각 체크의 상태는 카탈로그가 선언한 소스(HealthSource)로 판정한다.
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

    private final FeedHealthRegistry feedHealthRegistry;
    private final HealthHeartbeat healthHeartbeat;
    private final HealthCheckEventRepository eventRepository;
    private final MetricCollectorService metricCollectorService;

    /** 소스별 판정 결과(상태·판정근거) — 임계 문구는 카탈로그 항목이 스스로 제공(thresholdText) */
    private record Judgement(HealthStatus status, String detail) { }

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
                case INFRA, EVENT -> judgeFromEvents(c.key());
            };

            List<HealthCheckView.Failure> recent = new ArrayList<>();
            for (HealthCheckEvent e : eventRepository.findTop3ByCheckKeyOrderByLastFailedAtDesc(c.key())) {
                recent.add(new HealthCheckView.Failure(
                        fmt(e.getLastFailedAt()), e.getStatus(), e.getCause(), fmt(e.getResolvedAt())
                ));
            }
            String lastFailedAt = recent.isEmpty() ? null : recent.get(0).at();
            String lastCause = recent.isEmpty() ? null : recent.get(0).cause();

            out.add(new HealthCheckView(
                    c.key(), c.label(), c.description(),
                    c.layer().label(), c.layer().name(), c.priority().label(),
                    j.status(), j.detail(), c.thresholdText(),
                    lastFailedAt, lastCause, recent
            ));
        }
        return out;
    }

    /** 최근 실패 이력 (최신순 100건) — 컨트롤러 직렬화용 타입드 뷰로 변환해 반환 */
    public List<HealthEventView> getRecentEvents() {
        return eventRepository.findTop100ByOrderByLastFailedAtDesc().stream()
                .map(e -> new HealthEventView(
                        e.getCheckKey(), e.getStatus(), e.getSeverity(), e.getCause(),
                        fmt(e.getFirstFailedAt()), fmt(e.getLastFailedAt()), fmt(e.getResolvedAt())
                ))
                .toList();
    }

    private static String fmt(java.time.LocalDateTime t) {
        return t == null ? null : t.format(TS);
    }

    // ── 소스별 판정 ─────────────────────────────────────────────────

    private static Judgement judgeFeed(FeedHealthRegistry.FeedHealth fh) {
        HealthStatus status = fh == null ? HealthStatus.UNKNOWN : fh.status();
        return new Judgement(status, describeFeed(fh));
    }

    private Judgement judgeHeartbeat(String key) {
        HealthHeartbeat.Beat beat = healthHeartbeat.evaluate(key);
        return new Judgement(beat.status(), describeBeat(beat));
    }

    private Judgement judgeResource(String key) {
        Double value = resourceValue(key);
        HealthStatus status = value == null ? HealthStatus.UNKNOWN : StatusLadder.RESOURCE_PCT.judge(value);
        return new Judgement(status, describeResource(value));
    }

    private Judgement judgeRawTable() {
        StatusLadder.Judged j = StatusLadder.judgeRawTable(metricCollectorService.getLastRawAggTradeBytes());
        return new Judgement(j.status(), j.detail());
    }

    private Judgement judgeWsConn() {
        StatusLadder.Judged j = StatusLadder.judgeWsConn(metricCollectorService.getLastWsConnections());
        return new Judgement(j.status(), j.detail());
    }

    // 평가기·호출지점 push 가 이벤트로 적립한 결과를 읽는다(INFRA·EVENT 소스 공용).
    // 미복구(open) 이벤트 있으면 그 상태, 없으면 정상(UP). "알려진 실패 없음" 낙관 표시.
    private Judgement judgeFromEvents(String key) {
        HealthCheckEvent open =
                eventRepository.findTopByCheckKeyAndResolvedAtIsNullOrderByLastFailedAtDesc(key);
        if (open == null) {
            return new Judgement(HealthStatus.UP, "정상");
        }
        return new Judgement(open.getStatus().asHealthStatus(),
                open.getCause() != null ? open.getCause() : "이상 감지");
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
