// [AGENT] 헬스 체크 보드 집계 서비스
// 카탈로그 33개를 전부 나열하고, 신호가 있는 체크(피드)는 실연동, 나머지는 UNKNOWN(미구현) 표시.
// 팝오버 강화: 설명·판정근거·체크별 최근 실패 이력(3건) 포함.
package com.chs.springboot.global.monitor.health;

import com.chs.springboot.global.monitor.feed.FeedHealthConfig;
import com.chs.springboot.global.monitor.feed.FeedHealthRegistry;
import com.chs.springboot.global.monitor.feed.FeedStatus;
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

    private final FeedHealthRegistry feedHealthRegistry;
    private final HealthHeartbeat healthHeartbeat;
    private final HealthCheckEventRepository eventRepository;

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
