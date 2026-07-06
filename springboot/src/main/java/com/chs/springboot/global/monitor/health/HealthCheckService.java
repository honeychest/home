// [AGENT] 헬스 체크 보드 집계 서비스
// 카탈로그 34개를 전부 나열하고, 각 체크의 상태는 카탈로그가 선언한 소스(HealthSource)로 판정한다.
// 팝오버 강화: 설명·판정근거·체크별 최근 실패 이력(3건) 포함.
package com.chs.springboot.global.monitor.health;

import com.chs.springboot.global.monitor.feed.FeedHealthRegistry;
import com.chs.springboot.global.monitor.service.MetricCollectorService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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
    private final HealthClusterSnapshot healthClusterSnapshot;

    // 현재 정상이어도 이 시간 안에 복구된 장애가 있으면 '최근이상' 흔적을 표시(클릭 없이 인지). 0 이하면 비활성.
    @Value("${monitor.health.recent-window-hours:24}")
    private int recentWindowHours;

    /** 보드에 표시할 전체 체크 목록 */
    public List<HealthCheckView> getChecks() {
        Map<String, FeedHealthRegistry.FeedHealth> feeds = new HashMap<>();
        for (FeedHealthRegistry.FeedHealth f : feedHealthRegistry.snapshot()) {
            feeds.put(f.feedId(), f);
        }
        HealthSource.ClusterView cluster = HealthSource.ClusterView.from(
                healthClusterSnapshot.read().orElse(null));
        HealthSource.Ports ports = new HealthSource.Ports(
                feeds, healthHeartbeat, metricCollectorService, eventRepository, cluster);

        LocalDateTime recentCutoff = recentWindowHours > 0
                ? LocalDateTime.now().minusHours(recentWindowHours) : null;

        List<HealthCheckView> out = new ArrayList<>(HealthCheckCatalog.all().size());
        for (HealthCheckCatalog c : HealthCheckCatalog.all()) {
            HealthSource.Judgement j = c.source().judge(c, ports);

            List<HealthCheckView.Failure> recent = new ArrayList<>();
            String recoveredAt = null; // 창 안에서 복구된 가장 최근 시각(최신순 조회라 첫 매치가 최신)
            for (HealthCheckEvent e : eventRepository.findTop3ByCheckKeyOrderByLastFailedAtDesc(c.key())) {
                recent.add(new HealthCheckView.Failure(
                        fmt(e.getLastFailedAt()), e.getStatus(), e.getCause(), fmt(e.getResolvedAt())
                ));
                if (recoveredAt == null && recentCutoff != null
                        && e.getResolvedAt() != null && e.getLastFailedAt() != null
                        && e.getLastFailedAt().isAfter(recentCutoff)) {
                    recoveredAt = fmt(e.getResolvedAt());
                }
            }
            // 흔적은 '현재 정상(UP)'일 때만 — DEGRADED/DOWN 은 라이브 색이 이미 알림
            boolean recentlyRecovered = j.status() == HealthStatus.UP && recoveredAt != null;
            if (!recentlyRecovered) {
                recoveredAt = null;
            }
            String lastFailedAt = recent.isEmpty() ? null : recent.get(0).at();
            String lastCause = recent.isEmpty() ? null : recent.get(0).cause();

            out.add(new HealthCheckView(
                    c.key(), c.label(), c.description(),
                    c.layer().label(), c.layer().name(), c.priority().label(),
                    j.status(), j.detail(), c.thresholdText(),
                    lastFailedAt, lastCause, recentlyRecovered, recoveredAt, recent
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
}
