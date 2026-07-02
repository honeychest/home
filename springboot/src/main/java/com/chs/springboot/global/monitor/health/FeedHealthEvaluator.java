// [AGENT] L2 피드 카테고리 완성 계측 — 피드 상태 전환을 감지해 health_check_event 로 적립.
// FeedHealthRegistry(현재 상태) → HealthCheckRecorder(실패/복구 저장) 를 잇는 완결 루프 샘플.
// 5초마다 3개 피드(ticker/aggTrade/upbit) 상태를 평가한다. 정상 지속 시에는 DB 쓰기 없음.
package com.chs.springboot.global.monitor.health;

import com.chs.springboot.global.monitor.feed.FeedHealthRegistry;
import com.chs.springboot.global.monitor.feed.FeedStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

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

    @Scheduled(fixedDelay = 5000)
    public void evaluate() {
        for (FeedHealthRegistry.FeedHealth fh : feedHealthRegistry.snapshot()) {
            String checkKey = FEED_TO_CHECK.get(fh.feedId());
            if (checkKey == null) {
                continue;
            }
            FeedStatus status = fh.status();
            if (status == FeedStatus.DOWN) {
                recorder.markFail(checkKey, HealthStatus.DOWN, "CRITICAL", buildCause(fh));
            } else if (status == FeedStatus.STALE) {
                recorder.markFail(checkKey, HealthStatus.DEGRADED, "WARN", buildCause(fh));
            } else {
                recorder.markOk(checkKey);
            }
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
