package com.chs.springboot.global.redis;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LeaderElectionService 의 Lua fence 스크립트를 실제 localhost Redis 에 대고 원자성까지 검증하는
 * 선택 실행 통합 테스트. localhost:6379 가 응답하지 않으면 {@link Assumptions#assumeTrue} 로
 * 스킵되며(실패 아님) — 그래서 CI/기본 `./gradlew test` 는 Redis 없이도 항상 통과한다.
 * <p>
 * 운영 키(server:leader 등)는 절대 건드리지 않는다 — 이 스크립트들은 키 이름을 인자로 받으므로
 * 이 테스트 전용 키(test:leader-fence-it:*)로만 acquire/renew/release 를 수행하고,
 * 종료 시 그 두세 키만 DEL 한다(FLUSHALL/FLUSHDB 없음).
 */
class LeaderElectionRedisIntegrationTest {

    private static final String LEADER_KEY = "test:leader-fence-it:leader:" + UUID.randomUUID();
    private static final String FENCE_KEY = "test:leader-fence-it:fence:" + UUID.randomUUID();
    private static final String EPOCH_KEY = "test:leader-fence-it:epoch:" + UUID.randomUUID();
    private static final Duration TTL = Duration.ofSeconds(10);

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
                    redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Object>)
                            connection -> connection.ping())));
        } catch (Exception e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "localhost:6379 Redis 가 없어 선택 실행 통합 테스트를 스킵합니다.");
    }

    @AfterEach
    void tearDown() {
        // setUp 의 assumeTrue 가 실패(스킵)해도 JUnit5 는 @AfterEach 를 실행하므로,
        // reachable 이 아니면 Redis 호출을 시도조차 하지 않는다(연결 예외로 스킵이 실패로 뒤집히는 것을 방지).
        if (reachable && redisTemplate != null) {
            redisTemplate.delete(List.of(LEADER_KEY, FENCE_KEY, EPOCH_KEY));
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void acquire_thenAba_expiredOwnerCannotRenewOrStealBackFromNewOwner() {
        String ownerA = "owner-A-" + UUID.randomUUID();
        String ownerB = "owner-B-" + UUID.randomUUID();

        Long epoch1 = redisTemplate.execute(LeaderElectionService.ACQUIRE_IF_ABSENT,
                List.of(LEADER_KEY, FENCE_KEY, EPOCH_KEY), "server-A", ownerA, String.valueOf(TTL.toMillis()));
        assertThat(epoch1).isEqualTo(1L);

        // A 가 두 번째로 acquire 를 시도해도(이미 자신이 쥐고 있어도) exists 체크로 거부된다 — renew 경로만 정상 경로.
        Long reacquireByA = redisTemplate.execute(LeaderElectionService.ACQUIRE_IF_ABSENT,
                List.of(LEADER_KEY, FENCE_KEY, EPOCH_KEY), "server-A", ownerA, String.valueOf(TTL.toMillis()));
        assertThat(reacquireByA).isEqualTo(0L);

        // A 정상 renew
        Long renewOk = redisTemplate.execute(LeaderElectionService.RENEW_IF_OWNER,
                List.of(LEADER_KEY, FENCE_KEY), ownerA, String.valueOf(TTL.toMillis()));
        assertThat(renewOk).isEqualTo(1L);

        // lease 가 TTL 로 자연 만료된 상황을 시뮬레이션(release 없이 그냥 지움) — 강제 종료·네트워크 단절 재현
        redisTemplate.delete(List.of(LEADER_KEY, FENCE_KEY));

        // B 가 새 epoch 로 획득
        Long epoch2 = redisTemplate.execute(LeaderElectionService.ACQUIRE_IF_ABSENT,
                List.of(LEADER_KEY, FENCE_KEY, EPOCH_KEY), "server-B", ownerB, String.valueOf(TTL.toMillis()));
        assertThat(epoch2).isEqualTo(2L);

        // A 가 뒤늦게 renew 시도 → owner token 불일치로 거부
        Long staleRenew = redisTemplate.execute(LeaderElectionService.RENEW_IF_OWNER,
                List.of(LEADER_KEY, FENCE_KEY), ownerA, String.valueOf(TTL.toMillis()));
        assertThat(staleRenew).isEqualTo(0L);

        // A 가 뒤늦게 release 시도 → B 의 lease 를 지우지 못함(stale release 차단)
        Long staleRelease = redisTemplate.execute(LeaderElectionService.RELEASE_IF_OWNER,
                List.of(LEADER_KEY, FENCE_KEY), ownerA);
        assertThat(staleRelease).isEqualTo(0L);
        assertThat(redisTemplate.opsForValue().get(LEADER_KEY)).isEqualTo("server-B");
        assertThat(redisTemplate.opsForValue().get(FENCE_KEY)).isEqualTo(ownerB + ":2");

        // B 는 정상적으로 자신의 lease 를 반납할 수 있다
        Long releaseOk = redisTemplate.execute(LeaderElectionService.RELEASE_IF_OWNER,
                List.of(LEADER_KEY, FENCE_KEY), ownerB);
        assertThat(releaseOk).isEqualTo(1L);
        assertThat(redisTemplate.opsForValue().get(LEADER_KEY)).isNull();
        assertThat(redisTemplate.opsForValue().get(FENCE_KEY)).isNull();
    }

    @Test
    void concurrentAcquireAttempts_onlyOneWinsAndEpochIsMonotonic() throws InterruptedException {
        int attempts = 20;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(attempts);
        java.util.List<Long> results = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(attempts);
        for (int i = 0; i < attempts; i++) {
            String owner = "owner-" + i;
            pool.submit(() -> {
                try {
                    Long result = redisTemplate.execute(LeaderElectionService.ACQUIRE_IF_ABSENT,
                            List.of(LEADER_KEY, FENCE_KEY, EPOCH_KEY), "server-X", owner, String.valueOf(TTL.toMillis()));
                    results.add(result);
                } finally {
                    latch.countDown();
                }
            });
        }
        assertThat(latch.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        long winners = results.stream().filter(r -> r != null && r > 0).count();
        assertThat(winners).isEqualTo(1L);
        assertThat(results).contains(1L);
    }
}
