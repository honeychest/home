package com.chs.springboot.global.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.extension.TestWatcher;
import org.mockito.invocation.InvocationOnMock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * LeaderElectionService — owner token + epoch fence 단위 테스트.
 * <p>
 * 실제 Redis Lua 스크립트를 실행하지 않는 대신, 그 스크립트와 동일한 원자 검증 로직을
 * {@link FakeLeaderRedis} 로 재현해 두 인스턴스(JVM) 간 ABA·stale release 시나리오를 검증한다.
 * 실제 Lua 원자성 자체는 {@link LeaderElectionRedisIntegrationTest}(선택 실행)가 담당한다.
 * <p>
 * 콘솔에서 {@code [TEST START]} / {@code [PASSED]} / {@code [FAILED]} 로 테스트 단위 구분.
 */
class LeaderElectionServiceTest {

    private static final Logger log = LoggerFactory.getLogger(LeaderElectionServiceTest.class);
    private static final String SERVER_LEADER_KEY = "server:leader";
    private static final String FENCE_KEY = "server:leader:fence";
    private static final String EPOCH_COUNTER_KEY = "server:leader:epoch:counter";

    @RegisterExtension
    static final TestWatcher RESULT_LOG = new TestWatcher() {
        @Override
        public void testSuccessful(ExtensionContext context) {
            log.info("└── [PASSED] {} — {}", context.getDisplayName(), context.getRequiredTestMethod().getName());
        }

        @Override
        public void testFailed(ExtensionContext context, Throwable cause) {
            log.warn("└── [FAILED] {} — {} — {}",
                    context.getDisplayName(),
                    context.getRequiredTestMethod().getName(),
                    cause.getMessage());
        }
    };

    /** 두 LeaderElectionService(=두 JVM 인스턴스)가 공유하는 가짜 Redis — 실제 Lua 스크립트와 동일한 원자 검증 규칙을 재현. */
    private static final class FakeLeaderRedis {
        private final Map<String, String> store = new HashMap<>();

        synchronized long acquireIfAbsent(String serverName, String ownerToken) {
            if (store.containsKey(SERVER_LEADER_KEY)) {
                return 0L;
            }
            long epoch = Long.parseLong(store.getOrDefault(EPOCH_COUNTER_KEY, "0")) + 1;
            store.put(EPOCH_COUNTER_KEY, String.valueOf(epoch));
            store.put(SERVER_LEADER_KEY, serverName);
            store.put(FENCE_KEY, ownerToken + ":" + epoch);
            return epoch;
        }

        synchronized long renewIfOwner(String ownerToken) {
            String fence = store.get(FENCE_KEY);
            if (fence == null || !ownerOf(fence).equals(ownerToken)) {
                return 0L;
            }
            if (!store.containsKey(SERVER_LEADER_KEY)) {
                return 0L;
            }
            return 1L;
        }

        synchronized long releaseIfOwner(String ownerToken) {
            String fence = store.get(FENCE_KEY);
            if (fence == null || !ownerOf(fence).equals(ownerToken)) {
                return 0L;
            }
            store.remove(SERVER_LEADER_KEY);
            store.remove(FENCE_KEY);
            return 1L;
        }

        /** kill -9 등으로 release 없이 TTL 만 지나 lease 가 자연 만료된 상황을 시뮬레이션. */
        synchronized void expireLeaseWithoutRelease() {
            store.remove(SERVER_LEADER_KEY);
            store.remove(FENCE_KEY);
        }

        private String ownerOf(String fence) {
            return fence.substring(0, fence.indexOf(':'));
        }
    }

    private FakeLeaderRedis fakeRedis;
    private StringRedisTemplate redisTemplate;
    private ApplicationEventPublisher eventPublisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp(TestInfo testInfo) {
        log.info("┌── [TEST START] {} — {}",
                testInfo.getDisplayName(),
                testInfo.getTestMethod().map(m -> m.getName()).orElse("?"));

        fakeRedis = new FakeLeaderRedis();
        redisTemplate = mock(StringRedisTemplate.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        when(redisTemplate.execute(eq(LeaderElectionService.ACQUIRE_IF_ABSENT), anyList(), any(), any(), any()))
                .thenAnswer((InvocationOnMock inv) -> fakeRedis.acquireIfAbsent(inv.getArgument(2), inv.getArgument(3)));
        when(redisTemplate.execute(eq(LeaderElectionService.RENEW_IF_OWNER), anyList(), any(), any()))
                .thenAnswer((InvocationOnMock inv) -> fakeRedis.renewIfOwner(inv.getArgument(2)));
        when(redisTemplate.execute(eq(LeaderElectionService.RELEASE_IF_OWNER), anyList(), any()))
                .thenAnswer((InvocationOnMock inv) -> fakeRedis.releaseIfOwner(inv.getArgument(2)));
    }

    private LeaderElectionService newService(String serverName, String ownerToken) {
        LeaderElectionService service = new LeaderElectionService(redisTemplate, eventPublisher,
                mock(com.chs.springboot.global.monitor.health.HealthHeartbeat.class));
        ReflectionTestUtils.setField(service, "serverName", serverName);
        ReflectionTestUtils.setField(service, "ownerToken", ownerToken);
        return service;
    }

    @Test
    @DisplayName("비어 있는 lease → 획득 성공, epoch=1 발급, 이벤트 1회 발행")
    void refreshLeadership_acquiresWhenLeaseEmpty() {
        LeaderElectionService service = newService("server-A", "token-A");

        service.refreshLeadership();

        assertThat(service.isLeader()).isTrue();
        assertThat(service.getCurrentEpoch()).isEqualTo(1L);
        verify(eventPublisher).publishEvent(new LeadershipChangedEvent("server-A", true, "token-A", 1L));
    }

    @Test
    @DisplayName("다른 owner token 이 이미 lease 를 쥐고 있으면 serverName 이 같아도 리더가 되지 않는다 (stale writer 취약점 회귀 방지)")
    void refreshLeadership_sameServerNameDifferentToken_notLeader() {
        // server-A 라는 이름의 옛 프로세스가 이미(다른 token 으로) lease 를 쥐고 있는 상태를 재현
        fakeRedis.acquireIfAbsent("server-A", "old-process-token");

        LeaderElectionService restarted = newService("server-A", "new-process-token");
        restarted.refreshLeadership();

        assertThat(restarted.isLeader()).isFalse();
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("이미 리더 + renew 성공 → 재획득 시도 없음, epoch 그대로")
    void refreshLeadership_renewSucceeds_noReacquire() {
        LeaderElectionService service = newService("server-A", "token-A");
        service.refreshLeadership();
        clearInvocations(eventPublisher);

        service.refreshLeadership();

        assertThat(service.isLeader()).isTrue();
        assertThat(service.getCurrentEpoch()).isEqualTo(1L);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("같은 인스턴스 자가 재획득: leader=true 를 유지한 채 epoch 만 바뀌어도 이벤트가 발행된다(HIGH 결함 회귀 방지)")
    void refreshLeadership_selfReacquireWhileStillLeader_epochChangePublishesEvent() {
        LeaderElectionService service = newService("server-A", "token-A");
        service.refreshLeadership();
        assertThat(service.getCurrentEpoch()).isEqualTo(1L);
        clearInvocations(eventPublisher);

        // 같은 인스턴스가 스스로 리더라고 믿는 상태(isLeader=true)에서, lease 가 TTL 로 잠깐 만료됐지만
        // 아무도 채가지 않아 다음 tick 에 자기 자신이 새 epoch 로 재획득하는 상황(GC 정지·스케줄 지연 등).
        fakeRedis.expireLeaseWithoutRelease();

        service.refreshLeadership();

        assertThat(service.isLeader()).isTrue();
        assertThat(service.getCurrentEpoch()).isEqualTo(2L);
        // leader boolean 은 계속 true 였지만 epoch 가 바뀌었으므로 이벤트가 발행돼야 한다 —
        // 그렇지 않으면 telemetry 등 fence 캐시가 옛 epoch(1)에 멈춰, 실제로는 정당한 새 epoch(2)
        // 리더인데도 이후 flush 가 계속 stale fence 로 거부된다.
        verify(eventPublisher).publishEvent(new LeadershipChangedEvent("server-A", true, "token-A", 2L));
    }

    @Test
    @DisplayName("ABA 방지: A 가 리더인 동안 만료→B 가 새 epoch 로 획득하면, A 의 renew 는 실패하고 A 는 재획득도 못한다")
    void refreshLeadership_abaPrevention_expiredOwnerCannotRenewOrReacquireOverNewLeader() {
        LeaderElectionService serverA = newService("server-A", "token-A");
        serverA.refreshLeadership();
        assertThat(serverA.getCurrentEpoch()).isEqualTo(1L);

        // A 의 lease 가 TTL 로 자연 만료(A 는 아직 스스로 리더라고 믿는 중) → B 가 새 epoch 로 획득
        fakeRedis.expireLeaseWithoutRelease();
        LeaderElectionService serverB = newService("server-B", "token-B");
        serverB.refreshLeadership();
        assertThat(serverB.isLeader()).isTrue();
        assertThat(serverB.getCurrentEpoch()).isEqualTo(2L);

        // A 가 다음 5초 tick 에서 renew 를 시도 → fence owner 불일치로 실패해야 하고,
        // 뒤이은 재획득 시도도 B 가 이미 쥔 lease 때문에 실패해야 한다(ABA로 되돌아가지 않음)
        serverA.refreshLeadership();

        assertThat(serverA.isLeader()).isFalse();
        assertThat(serverB.isLeader()).isTrue();
        assertThat(serverB.getCurrentEpoch()).isEqualTo(2L); // B 의 epoch 은 A 개입으로 흔들리지 않는다
    }

    @Test
    @DisplayName("stale release 차단: 만료된 옛 owner 의 release 는 새 리더의 lease 를 지우지 못한다")
    void releaseLeadership_staleOwner_cannotDeleteNewLeadersLease() {
        LeaderElectionService serverA = newService("server-A", "token-A");
        serverA.refreshLeadership();
        fakeRedis.expireLeaseWithoutRelease();
        LeaderElectionService serverB = newService("server-B", "token-B");
        serverB.refreshLeadership();

        // A 는 스스로 아직 리더라고 믿는 상태로 shutdown 되어 release 를 시도 — 하지만 fence owner 가 B 라 거부돼야 한다
        ReflectionTestUtils.setField(serverA, "isLeader", true);
        serverA.releaseLeadership();

        assertThat(fakeRedis.store.get(SERVER_LEADER_KEY)).isEqualTo("server-B");
        assertThat(fakeRedis.store.get(FENCE_KEY)).isEqualTo("token-B:2");
    }

    @Test
    @DisplayName("releaseLeadership: 정상 리더는 자신의 lease 를 지울 수 있다")
    void releaseLeadership_whenLeader_deletesOwnLease() {
        LeaderElectionService service = newService("server-A", "token-A");
        service.refreshLeadership();
        clearInvocations(eventPublisher);

        service.releaseLeadership();

        assertThat(service.isLeader()).isFalse();
        assertThat(fakeRedis.store).doesNotContainKey(SERVER_LEADER_KEY);
        assertThat(fakeRedis.store).doesNotContainKey(FENCE_KEY);
        verify(eventPublisher).publishEvent(new LeadershipChangedEvent("server-A", false, "token-A", 1L));
    }

    @Test
    @DisplayName("releaseLeadership: 비리더면 Redis 호출 없음")
    void releaseLeadership_whenNotLeader_noRedisCall() {
        LeaderElectionService service = newService("server-A", "token-A");

        service.releaseLeadership();

        verify(redisTemplate, never()).execute(eq(LeaderElectionService.RELEASE_IF_OWNER), anyList(), any());
    }

    @Test
    @DisplayName("Redis 예외 시 리더 플래그 false, 이벤트 발행")
    void refreshLeadership_redisThrows_clearsLeader() {
        LeaderElectionService service = newService("server-A", "token-A");
        service.refreshLeadership();
        clearInvocations(eventPublisher);

        when(redisTemplate.execute(eq(LeaderElectionService.RENEW_IF_OWNER), anyList(), any(), any()))
                .thenThrow(new RuntimeException("redis down"));

        service.refreshLeadership();

        assertThat(service.isLeader()).isFalse();
        verify(eventPublisher).publishEvent(new LeadershipChangedEvent("server-A", false, "token-A", 1L));
    }

    @Test
    @DisplayName("getCurrentLeaderName: Redis 값을 그대로 반환한다(이 인스턴스가 리더인지와 무관)")
    void getCurrentLeaderName_returnsRedisValue() {
        LeaderElectionService service = newService("server-A", "token-A");
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(SERVER_LEADER_KEY)).thenReturn("server-B");

        assertThat(service.getCurrentLeaderName()).isEqualTo("server-B");
    }

    @Test
    @DisplayName("getCurrentLeaderName: Redis 예외 시 null(500으로 새지 않음)")
    void getCurrentLeaderName_redisThrows_returnsNull() {
        LeaderElectionService service = newService("server-A", "token-A");
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(SERVER_LEADER_KEY)).thenThrow(new RuntimeException("redis down"));

        assertThat(service.getCurrentLeaderName()).isNull();
    }
}
