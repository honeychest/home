// [AGENT] L2 feed-ws-reconnect 계측 — WS 스트림의 재연결/에러 이벤트를 60초 슬라이딩 창으로 집계.
// 각 스트림 서비스(Binance/Upbit)의 onError 콜백에서 record() 호출 → 창 내 횟수 임계로 thrashing 판정.
// 데이터 신선도(feed-*)와 별개로 "데이터는 와도 재연결이 잦은" 선행지표를 잡는다.
package com.chs.springboot.global.monitor.health;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

@Component
@RequiredArgsConstructor
public class WsReconnectMonitor {

    private static final String HEALTH_KEY = HealthCheckCatalog.FEED_WS_RECONNECT.key();
    private static final long WINDOW_MS = 60_000L;
    private static final int DEGRADED_THRESHOLD = 3; // 60초 내 재연결 ≥3 → 경고
    private static final int DOWN_THRESHOLD = 6;     // 60초 내 재연결 ≥6 → 다운

    private final Deque<Long> events = new ConcurrentLinkedDeque<>();
    private final HealthCheckRecorder recorder;

    /** 재연결/에러 이벤트 1건 기록(스트림 onError 훅에서 호출). */
    public void record(String label) {
        long now = System.currentTimeMillis();
        events.addLast(now);
        prune(now);
    }

    @Scheduled(fixedDelay = 20_000)
    public void evaluate() {
        long now = System.currentTimeMillis();
        prune(now);
        int count = events.size();
        String cause = "최근 60초 WS 재연결/에러 " + count + "회";
        if (count >= DOWN_THRESHOLD) {
            recorder.markFail(HEALTH_KEY, HealthStatus.DOWN, "CRITICAL", cause);
        } else if (count >= DEGRADED_THRESHOLD) {
            recorder.markFail(HEALTH_KEY, HealthStatus.DEGRADED, "WARN", cause);
        } else {
            recorder.markOk(HEALTH_KEY);
        }
    }

    private void prune(long now) {
        long cutoff = now - WINDOW_MS;
        Long head;
        while ((head = events.peekFirst()) != null && head < cutoff) {
            events.pollFirst();
        }
    }
}
