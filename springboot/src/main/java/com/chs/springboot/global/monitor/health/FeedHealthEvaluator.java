// [AGENT] L2 피드 카테고리 완성 계측 — 피드 상태 전환을 감지해 health_check_event 로 적립.
// FeedHealthRegistry(현재 상태) → HealthCheckRecorder(실패/복구 저장) 를 잇는 완결 루프 샘플.
// 5초마다 3개 피드(ticker/aggTrade/upbit) 상태를 평가한다. 정상 지속 시에는 DB 쓰기 없음.
package com.chs.springboot.global.monitor.health;

import com.chs.springboot.global.monitor.feed.FeedHealthRegistry;
import com.chs.springboot.global.redis.LeaderElectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class FeedHealthEvaluator {

    // FeedHealthRegistry feedId → 헬스 카탈로그 checkKey — 카탈로그의 feedId 선언에서 파생(단일 소스)
    private static final Map<String, String> FEED_TO_CHECK = buildFeedToCheck();

    private static Map<String, String> buildFeedToCheck() {
        Map<String, String> map = new HashMap<>();
        for (HealthCheckCatalog c : HealthCheckCatalog.all()) {
            if (c.source() == HealthSource.FEED) {
                map.put(c.feedId(), c.key());
            }
        }
        return Map.copyOf(map);
    }

    private final FeedHealthRegistry feedHealthRegistry;
    private final HealthCheckRecorder recorder;
    private final LeaderElectionService leaderElection;

    @Scheduled(fixedDelay = 5000)
    public void evaluate() {
        // 피드 WS 연결은 리더 노드만 소유(예: AggTradeStreamService) → 비리더는 수신 카운트가 항상 0.
        // 공유 DB 이벤트를 두고 리더(복구)와 비리더(DOWN)가 번갈아 열고 닫는 flapping을 막기 위해
        // 피드 소유자인 리더에서만 평가/기록한다. (HeartbeatWatchdog 등 다른 평가기와 동일한 게이트 패턴)
        if (!leaderElection.isLeader()) {
            return;
        }
        for (FeedHealthRegistry.FeedHealth fh : feedHealthRegistry.snapshot()) {
            String checkKey = FEED_TO_CHECK.get(fh.feedId());
            if (checkKey == null) {
                continue;
            }
            record(checkKey, fh);
        }
    }

    // 키별 격리(InfraHealthEvaluator/HeartbeatWatchdog와 동일 패턴) — 한 피드의 기록 실패가
    // 뒤 순서 피드의 평가까지 막지 않게.
    private void record(String checkKey, FeedHealthRegistry.FeedHealth fh) {
        try {
            recorder.record(checkKey, fh.status(), buildCause(fh));
        } catch (Exception e) {
            log.warn("[FeedHealth] {} 평가/기록 실패: {}", checkKey, e.getMessage());
        }
    }

    private static String buildCause(FeedHealthRegistry.FeedHealth fh) {
        String since = fh.secondsSinceLastMessage() == null
                ? "수신 기록 없음"
                : fh.secondsSinceLastMessage() + "초 전 마지막 수신";
        return "[%s] %s / %s / 누적 %d".formatted(
                fh.feedId(), fh.status().name(), since, fh.receivedCount());
    }
}
