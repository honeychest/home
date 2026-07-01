package com.chs.springboot.global.monitor.health;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class HeartbeatWatchdogTest {

    private static final String KEY = "sched-openinterest-poll";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-05-29T00:00:00Z"));

    private HealthHeartbeat heartbeatWith() {
        HealthHeartbeat hb = new HealthHeartbeat(clock);
        hb.register(KEY, new HealthHeartbeat.Spec(10, 30));
        return hb;
    }

    @Test
    void unknown_recordsNothing() {
        HealthHeartbeat hb = heartbeatWith();
        HealthCheckRecorder recorder = mock(HealthCheckRecorder.class);

        new HeartbeatWatchdog(hb, recorder).evaluate();

        verifyNoInteractions(recorder);
    }

    @Test
    void up_marksOk() {
        HealthHeartbeat hb = heartbeatWith();
        HealthCheckRecorder recorder = mock(HealthCheckRecorder.class);
        hb.beat(KEY);

        new HeartbeatWatchdog(hb, recorder).evaluate();

        verify(recorder).markOk(KEY);
    }

    @Test
    void stale_marksFailWarn() {
        HealthHeartbeat hb = heartbeatWith();
        HealthCheckRecorder recorder = mock(HealthCheckRecorder.class);
        Instant base = clock.instant();
        hb.beat(KEY);
        clock.setInstant(base.plusSeconds(10)); // DEGRADED

        new HeartbeatWatchdog(hb, recorder).evaluate();

        verify(recorder).markFail(eq(KEY), eq(HealthStatus.DEGRADED), eq("WARN"), anyString());
    }

    @Test
    void down_marksFailCritical() {
        HealthHeartbeat hb = heartbeatWith();
        HealthCheckRecorder recorder = mock(HealthCheckRecorder.class);
        Instant base = clock.instant();
        hb.beat(KEY);
        clock.setInstant(base.plusSeconds(30)); // DOWN

        new HeartbeatWatchdog(hb, recorder).evaluate();

        verify(recorder).markFail(eq(KEY), eq(HealthStatus.DOWN), eq("CRITICAL"), anyString());
    }

    @Test
    void explicitFail_marksFailCriticalWithCause() {
        HealthHeartbeat hb = heartbeatWith();
        HealthCheckRecorder recorder = mock(HealthCheckRecorder.class);
        hb.fail(KEY, "boom");

        new HeartbeatWatchdog(hb, recorder).evaluate();

        verify(recorder).markFail(KEY, HealthStatus.DOWN, "CRITICAL", "boom");
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
