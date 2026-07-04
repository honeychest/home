// [AGENT] 역할: 외부 데이터 피드의 마지막 수신시각 기록 + freshness(UP/DEGRADED/DOWN) 판정 | 주요메서드: register, markReceived, snapshot
// Purpose: 업스트림 피드가 "연결됐나"가 아니라 "데이터가 실제로 들어오고 있나"를 마지막 수신시각 기준으로 판정한다.
// 판정은 공용 StatusLadder(경과 초)로 한다. 한 번도 수신이 없으면 DOWN —
// 피드는 항상 흘러야 정상이므로, 비리더에서 안 도는 잡을 UNKNOWN 으로 두는 하트비트와 의도가 다르다.
package com.chs.springboot.global.monitor.feed;

import com.chs.springboot.global.monitor.health.HealthStatus;
import com.chs.springboot.global.monitor.health.StatusLadder;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public class FeedHealthRegistry {

    private final Clock clock;
    private final Map<String, StatusLadder> thresholds = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastReceived = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> receivedCounts = new ConcurrentHashMap<>();

    public FeedHealthRegistry(Clock clock) {
        this.clock = clock;
    }

    /** threshold 의 경고선/위험선 단위는 "마지막 수신 후 경과 초" */
    public void register(String feedId, StatusLadder threshold) {
        thresholds.put(feedId, threshold);
        receivedCounts.computeIfAbsent(feedId, k -> new LongAdder());
    }

    public void markReceived(String feedId) {
        lastReceived.put(feedId, clock.instant());
        receivedCounts.computeIfAbsent(feedId, k -> new LongAdder()).increment();
    }

    public List<FeedHealth> snapshot() {
        List<FeedHealth> out = new ArrayList<>(thresholds.size());
        for (Map.Entry<String, StatusLadder> entry : thresholds.entrySet()) {
            String feedId = entry.getKey();
            StatusLadder threshold = entry.getValue();
            Instant last = lastReceived.get(feedId);
            LongAdder counter = receivedCounts.get(feedId);
            long count = counter == null ? 0L : counter.sum();
            if (last == null) {
                out.add(new FeedHealth(feedId, HealthStatus.DOWN, null, null, count));
                continue;
            }
            long elapsed = clock.instant().getEpochSecond() - last.getEpochSecond();
            out.add(new FeedHealth(feedId, threshold.judge(elapsed), elapsed, last.toEpochMilli(), count));
        }
        return out;
    }

    public record FeedHealth(String feedId, HealthStatus status, Long secondsSinceLastMessage,
                             Long lastMessageAtEpochMs, long receivedCount) { }
}
