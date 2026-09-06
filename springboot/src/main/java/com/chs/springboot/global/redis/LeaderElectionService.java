// [AGENT] global/redis/LeaderElectionService.java
// 역할: Redis lease 기반 ServerLeader 선출 — owner token + epoch fence 로 stale writer 차단
// - refreshLeadership(): 5초마다 Redis Lua로 전역 서버 리더 획득/유지, TTL=10s
// - server:leader lease는 텔레그램 폴링과 Binance aggTrade WebSocket 수집의 단일 서버 실행을 보장
// - server:leader 의 값(=serverName)·TTL 계약은 그대로 유지(모니터링 대시보드가 원문 표시) —
//   fence(owner token·epoch)는 별도 키(server:leader:fence, server:leader:epoch:counter)에 둔다.
// - acquire 시에만 epoch 이 새로 발급되고(같은 JVM 재획득 포함), renew/release 는 owner token 일치를
//   Lua 로 원자 검증한다 — 재시작된 옛 인스턴스가 같은 serverName 이어도 새 lease 를 건드릴 수 없다.
// - isLeader(): 현재 서버가 ServerLeader인지 반환
// 연관: TelegramPollingService, AggTradeStreamService, AggTradeRawWriterKafkaTelemetryService(fence 검증), StringRedisTemplate
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
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LeaderElectionService {

    public static final String SERVER_LEADER_LEASE = "server-leader";
    private static final String SERVER_LEADER_KEY = "server:leader";

    /**
     * "{ownerToken}:{epoch}" 형태 — 현재 lease 를 실제로 쥔 JVM 인스턴스와 그 lease 세대를 함께 증명한다.
     * server:leader(값=serverName)와 TTL 을 같이 유지하되, 외부 표시 계약(서버 이름 문자열)은 건드리지 않는다.
     */
    public static final String FENCE_KEY = "server:leader:fence";
    /** 단조 증가 카운터. TTL 없음 — lease 가 만료·재획득돼도 epoch 은 절대 되돌아가지 않는다. */
    private static final String EPOCH_COUNTER_KEY = "server:leader:epoch:counter";
    private static final Duration TTL = Duration.ofSeconds(10);

    /** 리더가 비어 있을 때만 획득: epoch 발급 + leader/fence 키를 원자적으로 함께 쓴다. 실패 시 0. */
    static final RedisScript<Long> ACQUIRE_IF_ABSENT = new DefaultRedisScript<>(
            "if redis.call('exists', KEYS[1]) == 1 then return 0 end " +
                    "local epoch = redis.call('incr', KEYS[3]) " +
                    "redis.call('set', KEYS[1], ARGV[1], 'PX', ARGV[3]) " +
                    "redis.call('set', KEYS[2], ARGV[2] .. ':' .. tostring(epoch), 'PX', ARGV[3]) " +
                    "return epoch",
            Long.class);

    /** fence 의 owner token 이 일치할 때만 leader/fence 키 TTL 을 함께 갱신. epoch 은 올리지 않는다. */
    static final RedisScript<Long> RENEW_IF_OWNER = new DefaultRedisScript<>(
            "local fence = redis.call('get', KEYS[2]) " +
                    "if fence == false then return 0 end " +
                    "local sep = string.find(fence, ':') " +
                    "if sep == nil then return 0 end " +
                    "local token = string.sub(fence, 1, sep - 1) " +
                    "if token ~= ARGV[1] then return 0 end " +
                    "local ok = redis.call('pexpire', KEYS[1], ARGV[2]) " +
                    "if ok == 0 then return 0 end " +
                    "redis.call('pexpire', KEYS[2], ARGV[2]) " +
                    "return 1",
            Long.class);

    /** fence 의 owner token 이 일치할 때만 leader/fence 키를 함께 삭제(반납). */
    static final RedisScript<Long> RELEASE_IF_OWNER = new DefaultRedisScript<>(
            "local fence = redis.call('get', KEYS[2]) " +
                    "if fence == false then return 0 end " +
                    "local sep = string.find(fence, ':') " +
                    "if sep == nil then return 0 end " +
                    "local token = string.sub(fence, 1, sep - 1) " +
                    "if token ~= ARGV[1] then return 0 end " +
                    "redis.call('del', KEYS[1]) " +
                    "redis.call('del', KEYS[2]) " +
                    "return 1",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final HealthHeartbeat healthHeartbeat;

    // sched-leader-election: election 루프(5초)가 Redis 도달·정상 갱신 중인지. 전 노드에서 beat/fail.
    private static final String HEALTH_KEY = HealthCheckCatalog.SCHED_LEADER_ELECTION.key();

    @Value("${SERVER_NAME:LOCAL}")
    private String serverName;

    /** JVM 인스턴스마다 고유 — 같은 serverName 을 쓰는 재시작·중복 인스턴스를 구분하는 fence 식별자. */
    private final String ownerToken = UUID.randomUUID().toString();

    private volatile boolean isLeader = false;
    private volatile long currentEpoch = 0L;
    /** 마지막으로 이벤트로 통지한 epoch — leader=true 를 유지한 채 같은 인스턴스가 재획득해 epoch 만 바뀐 경우를 감지한다. */
    private volatile long lastNotifiedEpoch = -1L;

    /**
     * 5초마다 리더 상태 갱신/획득 시도
     */
    @Scheduled(fixedRate = 5000)
    public void refreshLeadership() {
        try {
            if (isLeader) {
                Long result = redisTemplate.execute(
                        RENEW_IF_OWNER,
                        List.of(SERVER_LEADER_KEY, FENCE_KEY),
                        ownerToken, String.valueOf(TTL.toMillis()));
                if (!Long.valueOf(1L).equals(result)) {
                    tryAcquire();
                }
            } else {
                tryAcquire();
            }
            healthHeartbeat.beat(HEALTH_KEY); // election 루프 정상 갱신(Redis 도달)
        } catch (Exception e) {
            log.error("Leader election error lease={} error={}", SERVER_LEADER_LEASE, e.getMessage(), e);
            healthHeartbeat.fail(HEALTH_KEY, e.getMessage());
            updateLeadership(false, currentEpoch);
        }
    }

    private void tryAcquire() {
        Long epoch = redisTemplate.execute(
                ACQUIRE_IF_ABSENT,
                List.of(SERVER_LEADER_KEY, FENCE_KEY, EPOCH_COUNTER_KEY),
                serverName, ownerToken, String.valueOf(TTL.toMillis()));

        if (epoch != null && epoch > 0) {
            currentEpoch = epoch;
            updateLeadership(true, epoch);
            log.info("[{}] ServerLeader 획득 owner={} epoch={}", serverName, ownerToken, epoch);
        } else {
            // 다른 인스턴스(또는 같은 serverName 의 옛 프로세스)가 이미 유효한 lease 를 쥐고 있음 —
            // serverName 문자열 일치만으로 리더로 간주하지 않는다(그게 stale writer 취약점이었다).
            updateLeadership(false, currentEpoch);
        }
    }

    @PreDestroy
    public void releaseLeadership() {
        if (isLeader) {
            redisTemplate.execute(RELEASE_IF_OWNER, List.of(SERVER_LEADER_KEY, FENCE_KEY), ownerToken);
            updateLeadership(false, currentEpoch);
            log.info("[{}] ServerLeader 반납 (shutdown)", serverName);
        }
    }

    public boolean isLeader() {
        return isLeader;
    }

    public String getServerName() {
        return serverName;
    }

    /**
     * 지금 lease 를 쥔 서버 이름을 Redis에서 직접 조회한다(이 인스턴스가 리더인지와 무관).
     * 비리더 인스턴스가 실제 리더로 요청을 내부 전달할 때 사용한다. Redis 장애 시 null.
     */
    public String getCurrentLeaderName() {
        try {
            return redisTemplate.opsForValue().get(SERVER_LEADER_KEY);
        } catch (Exception e) {
            log.warn("[{}] 현재 리더 이름 조회 실패 error={}", serverName, e.getMessage());
            return null;
        }
    }

    /** 현재(혹은 마지막으로 보유했던) lease 의 owner token — telemetry 등 fence 검증용 참고 노출. */
    public String getOwnerToken() {
        return ownerToken;
    }

    /** 현재(혹은 마지막으로 보유했던) lease 의 epoch. */
    public long getCurrentEpoch() {
        return currentEpoch;
    }

    /**
     * leader 상태 전이뿐 아니라, leader=true 를 유지한 채 같은 인스턴스가 재획득해 epoch·fence 가
     * 바뀐 경우(=lease 가 잠깐 만료됐다 아무도 못 채가서 자기 자신이 새 epoch 로 재획득한 경우)에도
     * 이벤트를 발행한다 — 그렇지 않으면 telemetry 등 fence 캐시가 옛 epoch 에 멈춰, 실제로는 정당한
     * 리더인데도 이후 flush 가 계속 stale fence 로 거부된다. 단순 renew(epoch 불변)는 애초에
     * updateLeadership 을 타지 않으므로(리더 유지 경로엔 renew 성공 시 호출이 없음) 중복 발행되지 않는다.
     */
    private void updateLeadership(boolean leader, long epoch) {
        boolean previous = isLeader;
        isLeader = leader;
        boolean leaderChanged = previous != leader;
        boolean epochChangedWhileLeader = leader && epoch != lastNotifiedEpoch;
        if (leaderChanged || epochChangedWhileLeader) {
            if (leader) {
                lastNotifiedEpoch = epoch;
            }
            eventPublisher.publishEvent(new LeadershipChangedEvent(serverName, leader, ownerToken, epoch));
        }
    }
}
