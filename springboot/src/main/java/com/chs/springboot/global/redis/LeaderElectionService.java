// [AGENT] global/redis/LeaderElectionService.java
// 역할: Redis lease 기반 ServerLeader 선출
// - refreshLeadership(): 5초마다 Redis SETNX로 전역 서버 리더 획득/유지, TTL=10s
// - server:leader lease는 텔레그램 폴링과 Binance aggTrade WebSocket 수집의 단일 서버 실행을 보장
// - isLeader(): 현재 서버가 ServerLeader인지 반환
// 연관: TelegramPollingService, StringRedisTemplate
package com.chs.springboot.global.redis;

import com.chs.springboot.global.monitor.health.HealthCheckCatalog;
import com.chs.springboot.global.monitor.health.HealthHeartbeat;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LeaderElectionService {

    public static final String SERVER_LEADER_LEASE = "server-leader";
    private static final String SERVER_LEADER_KEY = "server:leader";
    private static final Duration TTL = Duration.ofSeconds(10);

    private static final RedisScript<Long> EXPIRE_IF_OWNER = new DefaultRedisScript<>(
            "if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('pexpire',KEYS[1],ARGV[2]) else return 0 end",
            Long.class);

    private static final RedisScript<Long> DELETE_IF_OWNER = new DefaultRedisScript<>(
            "if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final HealthHeartbeat healthHeartbeat;

    // sched-leader-election: election 루프(5초)가 Redis 도달·정상 갱신 중인지. 전 노드에서 beat/fail.
    private static final String HEALTH_KEY = HealthCheckCatalog.SCHED_LEADER_ELECTION.key();

    @Value("${SERVER_NAME:LOCAL}")
    private String serverName;

    private volatile boolean isLeader = false;

    /**
     * 5초마다 리더 상태 갱신/획득 시도
     */
    @Scheduled(fixedRate = 5000)
    public void refreshLeadership() {
        try {
            if (isLeader) {
                Long result = redisTemplate.execute(
                        EXPIRE_IF_OWNER,
                        List.of(SERVER_LEADER_KEY),
                        serverName, String.valueOf(TTL.toMillis()));
                if (!Long.valueOf(1L).equals(result)) {
                    tryAcquire();
                }
            } else {
                tryAcquire();
            }
            healthHeartbeat.beat(HEALTH_KEY); // election 루프 정상 갱신(Redis 도달)
        } catch (Exception e) {
            log.error("Leader election error lease={} error={}", SERVER_LEADER_LEASE, e.getMessage());
            healthHeartbeat.fail(HEALTH_KEY, e.getMessage());
            updateLeadership(false);
        }
    }

    private void tryAcquire() {
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(SERVER_LEADER_KEY, serverName, TTL);

        if (Boolean.TRUE.equals(acquired)) {
            updateLeadership(true);
            log.info("[{}] ServerLeader 획득", serverName);
        } else {
            String currentLeader = redisTemplate.opsForValue().get(SERVER_LEADER_KEY);
            updateLeadership(serverName.equals(currentLeader));
        }
    }

    @PreDestroy
    public void releaseLeadership() {
        if (isLeader) {
            redisTemplate.execute(DELETE_IF_OWNER, List.of(SERVER_LEADER_KEY), serverName);
            updateLeadership(false);
            log.info("[{}] ServerLeader 반납 (shutdown)", serverName);
        }
    }

    public boolean isLeader() {
        return isLeader;
    }

    public String getServerName() {
        return serverName;
    }

    private void updateLeadership(boolean leader) {
        boolean previous = isLeader;
        isLeader = leader;
        if (previous != leader) {
            eventPublisher.publishEvent(new LeadershipChangedEvent(serverName, leader));
        }
    }
}
