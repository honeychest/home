package com.chs.springboot.global.monitor.health;

import com.chs.springboot.global.redis.LeaderElectionService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class HeartbeatWatchdogTest {

    private static final String KEY = "sched-openinterest-poll";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-05-29T00:00:00Z"));

    private HealthHeartbeat heartbeatWith() {
        HealthHeartbeat hb = new HealthHeartbeat(clock);
        hb.register(KEY, new HealthHeartbeat.Spec(10, 30));
        return hb;
    }

    // 비리더(isLeader 기본 false)라 클러스터 발행은 호출되지 않음 — record 전달 동작에 집중
    private HeartbeatWatchdog watchdog(HealthHeartbeat hb, HealthCheckRecorder recorder) {
        return new HeartbeatWatchdog(hb, recorder,
                mock(LeaderElectionService.class), mock(HealthClusterSnapshot.class));
    }

    @Test
    void unknown_passesUnknownToRecorder() {
        // UNKNOWN(관측 전) 무시 정책은 recorder.record 가 담당 — watchdog 은 그대로 전달한다
        HealthHeartbeat hb = heartbeatWith();
        HealthCheckRecorder recorder = mock(HealthCheckRecorder.class);

        watchdog(hb, recorder).evaluate();

        verify(recorder).record(eq(KEY), eq(HealthStatus.UNKNOWN), isNull());
    }

    @Test
    void up_recordsUp() {
        HealthHeartbeat hb = heartbeatWith();
        HealthCheckRecorder recorder = mock(HealthCheckRecorder.class);
        hb.beat(KEY);

        watchdog(hb, recorder).evaluate();

        verify(recorder).record(eq(KEY), eq(HealthStatus.UP), isNull());
    }

    @Test
    void stale_recordsDegraded() {
        HealthHeartbeat hb = heartbeatWith();
        HealthCheckRecorder recorder = mock(HealthCheckRecorder.class);
        Instant base = clock.instant();
        hb.beat(KEY);
        clock.setInstant(base.plusSeconds(10)); // DEGRADED

        watchdog(hb, recorder).evaluate();

        verify(recorder).record(eq(KEY), eq(HealthStatus.DEGRADED), anyString());
    }

    @Test
    void down_recordsDown() {
        HealthHeartbeat hb = heartbeatWith();
        HealthCheckRecorder recorder = mock(HealthCheckRecorder.class);
        Instant base = clock.instant();
        hb.beat(KEY);
        clock.setInstant(base.plusSeconds(30)); // DOWN

        watchdog(hb, recorder).evaluate();

        verify(recorder).record(eq(KEY), eq(HealthStatus.DOWN), anyString());
    }

    @Test
    void explicitFail_recordsDownWithCause() {
        HealthHeartbeat hb = heartbeatWith();
        HealthCheckRecorder recorder = mock(HealthCheckRecorder.class);
        hb.fail(KEY, "boom");

        watchdog(hb, recorder).evaluate();

        verify(recorder).record(KEY, HealthStatus.DOWN, "boom");
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
