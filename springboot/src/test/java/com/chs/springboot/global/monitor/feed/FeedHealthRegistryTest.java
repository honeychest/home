package com.chs.springboot.global.monitor.feed;

import com.chs.springboot.global.monitor.health.HealthStatus;
import com.chs.springboot.global.monitor.health.StatusLadder;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FeedHealthRegistryTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-05-29T00:00:00Z"));

    @Test
    void receivedJustNow_isUp() {
        FeedHealthRegistry registry = new FeedHealthRegistry(clock);
        registry.register("binance-ticker", new StatusLadder(10, 30));

        registry.markReceived("binance-ticker");

        List<FeedHealthRegistry.FeedHealth> snapshot = registry.snapshot();
        assertThat(snapshot).hasSize(1);
        assertThat(snapshot.get(0).feedId()).isEqualTo("binance-ticker");
        assertThat(snapshot.get(0).status()).isEqualTo(HealthStatus.UP);
        assertThat(snapshot.get(0).secondsSinceLastMessage()).isEqualTo(0L);
    }

    @Test
    void elapsedCrossesThresholds_upThenStaleThenDown() {
        FeedHealthRegistry registry = new FeedHealthRegistry(clock);
        registry.register("binance-ticker", new StatusLadder(10, 30));
        Instant base = clock.instant();
        registry.markReceived("binance-ticker");

        clock.setInstant(base.plusSeconds(9));
        assertThat(statusOf(registry, "binance-ticker")).isEqualTo(HealthStatus.UP);

        clock.setInstant(base.plusSeconds(10));
        assertThat(statusOf(registry, "binance-ticker")).isEqualTo(HealthStatus.DEGRADED);

        clock.setInstant(base.plusSeconds(29));
        assertThat(statusOf(registry, "binance-ticker")).isEqualTo(HealthStatus.DEGRADED);

        clock.setInstant(base.plusSeconds(30));
        assertThat(statusOf(registry, "binance-ticker")).isEqualTo(HealthStatus.DOWN);
    }

    @Test
    void neverReceived_isDown() {
        FeedHealthRegistry registry = new FeedHealthRegistry(clock);
        registry.register("upbit", new StatusLadder(10, 30));

        FeedHealthRegistry.FeedHealth health = registry.snapshot().get(0);
        assertThat(health.status()).isEqualTo(HealthStatus.DOWN);
        assertThat(health.secondsSinceLastMessage()).isNull();
    }

    @Test
    void received_exposesLastMessageTimeAndCumulativeCount() {
        FeedHealthRegistry registry = new FeedHealthRegistry(clock);
        registry.register("binance-aggTrade", new StatusLadder(10, 30));

        registry.markReceived("binance-aggTrade");
        registry.markReceived("binance-aggTrade");
        registry.markReceived("binance-aggTrade");

        FeedHealthRegistry.FeedHealth health = registry.snapshot().get(0);
        assertThat(health.receivedCount()).isEqualTo(3L);
        assertThat(health.lastMessageAtEpochMs()).isEqualTo(clock.instant().toEpochMilli());
    }

    @Test
    void neverReceived_countZeroAndLastMessageNull() {
        FeedHealthRegistry registry = new FeedHealthRegistry(clock);
        registry.register("upbit", new StatusLadder(10, 30));

        FeedHealthRegistry.FeedHealth health = registry.snapshot().get(0);
        assertThat(health.receivedCount()).isEqualTo(0L);
        assertThat(health.lastMessageAtEpochMs()).isNull();
    }

    private static HealthStatus statusOf(FeedHealthRegistry registry, String feedId) {
        return registry.snapshot().stream()
                .filter(h -> h.feedId().equals(feedId))
                .findFirst()
                .orElseThrow()
                .status();
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
