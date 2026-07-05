package com.chs.springboot.global.monitor.health;

import com.chs.springboot.global.monitor.service.MetricCollectorService;
import com.chs.springboot.global.redis.RedisKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HealthClusterSnapshotTest {

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final HealthHeartbeat heartbeat =
            new HealthHeartbeat(Clock.fixed(Instant.parse("2026-07-06T00:00:00Z"), ZoneOffset.UTC));
    private final MetricCollectorService metrics = mock(MetricCollectorService.class);
    private final HealthClusterSnapshot snapshot =
            new HealthClusterSnapshot(redis, mapper, heartbeat, metrics);

    @Test
    void publishThenReadRoundTrips() {
        when(redis.opsForValue()).thenReturn(ops);
        heartbeat.register("pipe-rollup-1s", new HealthHeartbeat.Spec(10, 30));
        heartbeat.beat("pipe-rollup-1s");
        when(metrics.getLastRam()).thenReturn(55d);
        when(metrics.getLastDisk()).thenReturn(-1d);              // 미관측 → null 로 발행
        when(metrics.getLastRawAggTradeBytes()).thenReturn(-1L);  // 미관측 → null
        when(metrics.getLastWsConnections()).thenReturn(42);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        snapshot.publish();
        verify(ops).set(eq(RedisKeys.HEALTH_SNAPSHOT), json.capture(), eq(60L), eq(TimeUnit.SECONDS));

        when(ops.get(RedisKeys.HEALTH_SNAPSHOT)).thenReturn(json.getValue());
        Optional<HealthClusterSnapshot.Dto> dto = snapshot.read();

        assertThat(dto).isPresent();
        assertThat(dto.get().ram()).isEqualTo(55d);
        assertThat(dto.get().disk()).isNull();
        assertThat(dto.get().rawTableBytes()).isNull();
        assertThat(dto.get().wsConnections()).isEqualTo(42);
        assertThat(dto.get().heartbeats()).hasSize(1);
        assertThat(dto.get().heartbeats().get(0).checkKey()).isEqualTo("pipe-rollup-1s");
        assertThat(dto.get().heartbeats().get(0).lastBeatEpochMs()).isNotNull();
    }

    @Test
    void readReturnsEmptyWhenAbsent() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(RedisKeys.HEALTH_SNAPSHOT)).thenReturn(null);

        assertThat(snapshot.read()).isEmpty();
    }
}
