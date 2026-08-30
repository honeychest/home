package com.chs.springboot.domain.binance.service.rawwriter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AggTradeRawWriterKafkaTelemetryService.FLUSH_IF_FENCE_VALID 의 원자성을 실제 localhost Redis 로
 * 검증하는 선택 실행 통합 테스트 — "이전 leader 가 늦게 flush 해도 새 leader 상태를 덮지 못한다"를
 * 실제 Lua 실행으로 증명한다. Redis 가 없으면 스킵(실패 아님). 운영 키는 건드리지 않고
 * 이 테스트 전용 키(test:telemetry-fence-it:*)만 쓰고 끝에 DEL 한다.
 */
class AggTradeRawWriterTelemetryFenceRedisIntegrationTest {

    private static final String CACHE_KEY = "test:telemetry-fence-it:cache:" + UUID.randomUUID();
    private static final String FENCE_KEY = "test:telemetry-fence-it:fence:" + UUID.randomUUID();

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private boolean reachable;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        try {
            reachable = "PONG".equalsIgnoreCase(String.valueOf(
                    redisTemplate.execute((RedisCallback<Object>) connection -> connection.ping())));
        } catch (Exception e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "localhost:6379 Redis 가 없어 선택 실행 통합 테스트를 스킵합니다.");
    }

    @AfterEach
    void tearDown() {
        if (reachable && redisTemplate != null) {
            redisTemplate.delete(List.of(CACHE_KEY, FENCE_KEY));
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void staleLeaderFlush_isRejectedAfterNewLeaderTakesFence() {
        String oldFence = "owner-old:1";
        String newFence = "owner-new:2";

        // LeaderElectionService 가 새 리더에게 발급한 fence 를 흉내낸다("지금 유효한 리더는 owner-new epoch2").
        redisTemplate.opsForValue().set(FENCE_KEY, newFence);

        Long newLeaderFlush = redisTemplate.execute(AggTradeRawWriterKafkaTelemetryService.FLUSH_IF_FENCE_VALID,
                List.of(CACHE_KEY, FENCE_KEY), newFence, "{\"totalConsumedRecords\":100}", "60");
        assertThat(newLeaderFlush).isEqualTo(1L);
        assertThat(redisTemplate.opsForValue().get(CACHE_KEY)).isEqualTo("{\"totalConsumedRecords\":100}");

        // 이전 리더가 뒤늦게(자신은 아직 owner-old:1 인 줄 알고) flush 를 시도 — fence 불일치로 거부돼야 한다
        Long staleFlush = redisTemplate.execute(AggTradeRawWriterKafkaTelemetryService.FLUSH_IF_FENCE_VALID,
                List.of(CACHE_KEY, FENCE_KEY), oldFence, "{\"totalConsumedRecords\":5}", "60");
        assertThat(staleFlush).isEqualTo(0L);

        // 새 리더가 먼저 심어둔 값(100)이 이전 리더의 stale 값(5)으로 덮이지 않아야 한다
        assertThat(redisTemplate.opsForValue().get(CACHE_KEY)).isEqualTo("{\"totalConsumedRecords\":100}");
    }
}
