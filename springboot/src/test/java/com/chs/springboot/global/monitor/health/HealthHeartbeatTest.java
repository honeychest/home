package com.chs.springboot.global.monitor.health;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class HealthHeartbeatTest {

    private static final String KEY = "sched-openinterest-poll";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-05-29T00:00:00Z"));

    private HealthHeartbeat newHeartbeat() {
        HealthHeartbeat hb = new HealthHeartbeat(clock);
        hb.register(KEY, new HealthHeartbeat.Spec(10, 30));
        return hb;
    }

    @Test
    void neverBeat_isUnknown() {
        HealthHeartbeat hb = newHeartbeat();

        HealthHeartbeat.Beat beat = hb.evaluate(KEY);
        assertThat(beat.status()).isEqualTo(HealthStatus.UNKNOWN);
        assertThat(beat.secondsSinceBeat()).isNull();
    }

    @Test
    void beatJustNow_isUp() {
        HealthHeartbeat hb = newHeartbeat();

        hb.beat(KEY);

        HealthHeartbeat.Beat beat = hb.evaluate(KEY);
        assertThat(beat.status()).isEqualTo(HealthStatus.UP);
        assertThat(beat.secondsSinceBeat()).isEqualTo(0L);
    }

    @Test
    void elapsedCrossesThresholds_upThenDegradedThenDown() {
        HealthHeartbeat hb = newHeartbeat();
        Instant base = clock.instant();
        hb.beat(KEY);

        clock.setInstant(base.plusSeconds(9));
        assertThat(hb.evaluate(KEY).status()).isEqualTo(HealthStatus.UP);

        clock.setInstant(base.plusSeconds(10));
        assertThat(hb.evaluate(KEY).status()).isEqualTo(HealthStatus.DEGRADED);

        clock.setInstant(base.plusSeconds(29));
        assertThat(hb.evaluate(KEY).status()).isEqualTo(HealthStatus.DEGRADED);

        clock.setInstant(base.plusSeconds(30));
        assertThat(hb.evaluate(KEY).status()).isEqualTo(HealthStatus.DOWN);
    }

    @Test
    void fail_isDown_withCause() {
        HealthHeartbeat hb = newHeartbeat();

        hb.fail(KEY, "BTCUSDT: timeout");

        HealthHeartbeat.Beat beat = hb.evaluate(KEY);
        assertThat(beat.status()).isEqualTo(HealthStatus.DOWN);
        assertThat(beat.cause()).isEqualTo("BTCUSDT: timeout");
    }

    @Test
    void beatAfterFail_recoversToUp() {
        HealthHeartbeat hb = newHeartbeat();
        hb.fail(KEY, "일시 실패");
        assertThat(hb.evaluate(KEY).status()).isEqualTo(HealthStatus.DOWN);

        hb.beat(KEY);

        assertThat(hb.evaluate(KEY).status()).isEqualTo(HealthStatus.UP);
    }

    @Test
    void unregisteredKey_isUnknown() {
        HealthHeartbeat hb = newHeartbeat();

        assertThat(hb.evaluate("nonexistent").status()).isEqualTo(HealthStatus.UNKNOWN);
        assertThat(hb.isRegistered("nonexistent")).isFalse();
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
