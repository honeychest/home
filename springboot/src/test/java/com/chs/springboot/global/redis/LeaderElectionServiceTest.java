package com.chs.springboot.global.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.extension.TestWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * LeaderElectionService — Redis SETNX/TTL/expire 경로 단위 테스트 (StringRedisTemplate 목)
 * <p>
 * 콘솔에서 {@code [TEST START]} / {@code [PASSED]} / {@code [FAILED]} 로 테스트 단위 구분.
 * 그 아래 {@code LeaderElectionService} 로그는 <b>피코드(서비스)</b>가 찍는 것이며, 통과 여부는 PASSED/FAILED 줄을 보면 됨.
 */
class LeaderElectionServiceTest {

    private static final Logger log = LoggerFactory.getLogger(LeaderElectionServiceTest.class);
    private static final String SERVER_LEADER_KEY = "server:leader";

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

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private ApplicationEventPublisher eventPublisher;
    private LeaderElectionService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp(TestInfo testInfo) {
        log.info("┌── [TEST START] {} — {}",
                testInfo.getDisplayName(),
                testInfo.getTestMethod().map(m -> m.getName()).orElse("?"));

        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), eq("server-A"), any(Duration.class))).thenReturn(false);
        when(valueOps.get(anyString())).thenReturn("server-B");
        service = new LeaderElectionService(redisTemplate, eventPublisher,
                mock(com.chs.springboot.global.monitor.health.HealthHeartbeat.class));
        ReflectionTestUtils.setField(service, "serverName", "server-A");
    }

    @Test
    @DisplayName("비리더 + setIfAbsent 성공 → 리더가 됨")
    void refreshLeadership_acquiresWhenSetIfAbsentTrue() {
        when(valueOps.setIfAbsent(eq(SERVER_LEADER_KEY), eq("server-A"), any(Duration.class))).thenReturn(true);

        service.refreshLeadership();

        assertThat(service.isLeader()).isTrue();
        verify(valueOps).setIfAbsent(eq(SERVER_LEADER_KEY), eq("server-A"), any(Duration.class));
        verify(eventPublisher).publishEvent(new LeadershipChangedEvent("server-A", true));
    }

    @Test
    @DisplayName("비리더 + 키는 있으나 값이 내 serverName → 리더로 간주")
    void refreshLeadership_sameServerInKey_staysLeader() {
        when(valueOps.setIfAbsent(eq(SERVER_LEADER_KEY), eq("server-A"), any(Duration.class))).thenReturn(false);
        when(valueOps.get(SERVER_LEADER_KEY)).thenReturn("server-A");

        service.refreshLeadership();

        assertThat(service.isLeader()).isTrue();
        verify(eventPublisher).publishEvent(new LeadershipChangedEvent("server-A", true));
    }

    @Test
    @DisplayName("비리더 + 다른 서버가 리더 → 비리더 유지")
    void refreshLeadership_otherServerOwnsKey_notLeader() {
        when(valueOps.setIfAbsent(eq(SERVER_LEADER_KEY), eq("server-A"), any(Duration.class))).thenReturn(false);
        when(valueOps.get(SERVER_LEADER_KEY)).thenReturn("server-B");

        service.refreshLeadership();

        assertThat(service.isLeader()).isFalse();
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("이미 리더 + Lua expire 성공 → setIfAbsent 재시도 없음")
    void refreshLeadership_leaderExpireTrue_noAcquire() {
        acquireTelegramLease();
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of(SERVER_LEADER_KEY)),
                eq("server-A"), anyString())).thenReturn(1L);

        service.refreshLeadership();

        assertThat(service.isLeader()).isTrue();
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(SERVER_LEADER_KEY)),
                eq("server-A"), anyString());
        verify(valueOps, never()).setIfAbsent(eq(SERVER_LEADER_KEY), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("이미 리더 + Lua expire 실패(소유자 불일치 또는 키 소실) → setIfAbsent로 재획득")
    void refreshLeadership_leaderExpireFalse_reacquires() {
        acquireTelegramLease();
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of(SERVER_LEADER_KEY)),
                eq("server-A"), anyString())).thenReturn(0L);
        when(valueOps.setIfAbsent(eq(SERVER_LEADER_KEY), eq("server-A"), any(Duration.class))).thenReturn(true);

        service.refreshLeadership();

        assertThat(service.isLeader()).isTrue();
        verify(valueOps).setIfAbsent(eq(SERVER_LEADER_KEY), eq("server-A"), any(Duration.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("Redis 예외 시 리더 플래그 false")
    void refreshLeadership_redisThrows_clearsLeader() {
        acquireTelegramLease();
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of(SERVER_LEADER_KEY)),
                eq("server-A"), anyString()))
                .thenThrow(new RuntimeException("redis down"));

        service.refreshLeadership();

        assertThat(service.isLeader()).isFalse();
        verify(eventPublisher).publishEvent(new LeadershipChangedEvent("server-A", false));
    }

    @Test
    @DisplayName("releaseLeadership: 리더일 때 Lua로 소유자 확인 후 삭제")
    void releaseLeadership_whenLeader_deletesKey() {
        acquireTelegramLease();

        service.releaseLeadership();

        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(SERVER_LEADER_KEY)), eq("server-A"));
        verify(eventPublisher).publishEvent(new LeadershipChangedEvent("server-A", false));
    }

    @Test
    @DisplayName("releaseLeadership: 비리더면 Lua 호출 없음")
    void releaseLeadership_whenNotLeader_noDelete() {
        service.releaseLeadership();

        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    @DisplayName("ServerLeader lease 획득 시 리더 이벤트 1회 발행")
    void refreshLeadership_serverLeaderLeaseAcquired_publishesLeaderEventOnce() {
        when(valueOps.setIfAbsent(eq(SERVER_LEADER_KEY), eq("server-A"), any(Duration.class))).thenReturn(true);

        service.refreshLeadership();
        clearInvocations(valueOps, redisTemplate);
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of(SERVER_LEADER_KEY)),
                eq("server-A"), anyString())).thenReturn(1L);
        service.refreshLeadership();

        assertThat(service.isLeader()).isTrue();
        verify(eventPublisher, times(1)).publishEvent(new LeadershipChangedEvent("server-A", true));
    }

    private void acquireTelegramLease() {
        when(valueOps.setIfAbsent(eq(SERVER_LEADER_KEY), eq("server-A"), any(Duration.class))).thenReturn(true);
        service.refreshLeadership();
        clearInvocations(valueOps, redisTemplate, eventPublisher);
    }
}
