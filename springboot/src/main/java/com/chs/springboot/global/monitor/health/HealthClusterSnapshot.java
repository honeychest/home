// [AGENT] 헬스 보드 클러스터 스냅샷 — 이중화(2노드)에서 보드가 어느 노드에 붙어도 같은 상태를 보게 한다.
// 문제: 하트비트(HealthHeartbeat)와 자원값(ram/disk/rawtable/ws)은 "리더에서만 관측되는" 노드-로컬 값이라,
//       보드가 비리더 노드에 붙으면 정상인데도 '대기(UNKNOWN)'로 뜬다.
// 해법: 리더가 원시 상태를 health:snapshot 한 키로 매 주기 전체 발행(monitor:snapshot 패턴과 동일).
//       비리더 보드는 이 키를 읽어 판정한다. 상태가 아닌 '원시 타임스탬프'를 담아 읽는 쪽이 재계산 → 주기와 무관하게 정확.
package com.chs.springboot.global.monitor.health;

import com.chs.springboot.global.monitor.service.MetricCollectorService;
import com.chs.springboot.global.redis.RedisKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class HealthClusterSnapshot {

    private static final long TTL_SECONDS = 60;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final HealthHeartbeat heartbeat;
    private final MetricCollectorService metrics;

    /** 발행 페이로드 — 하트비트 원시상태 + 리더 관측 자원값(미관측은 null). */
    public record Dto(List<HeartbeatEntry> heartbeats,
                      Double ram, Double disk, Long rawTableBytes, Integer wsConnections,
                      long publishedAtEpochMs) { }

    public record HeartbeatEntry(String checkKey, long staleSeconds, long downSeconds,
                                 Long lastBeatEpochMs, Long lastFailEpochMs, String cause) { }

    /** 리더만 호출 — 현재 하트비트 원시상태 + 자원값을 health:snapshot 한 키로 전체 발행. */
    public void publish() {
        List<HeartbeatEntry> beats = new ArrayList<>();
        for (HealthHeartbeat.RawBeat r : heartbeat.rawSnapshot()) {
            beats.add(new HeartbeatEntry(r.checkKey(), r.staleSeconds(), r.downSeconds(),
                    r.lastBeatEpochMs(), r.lastFailEpochMs(), r.cause()));
        }
        double ram = metrics.getLastRam();
        double disk = metrics.getLastDisk();
        long rawBytes = metrics.getLastRawAggTradeBytes();
        int wsConns = metrics.getLastWsConnections();
        Dto dto = new Dto(
                beats,
                ram < 0 ? null : ram,
                disk < 0 ? null : disk,
                rawBytes < 0 ? null : rawBytes,
                wsConns < 0 ? null : wsConns,
                System.currentTimeMillis());
        try {
            redisTemplate.opsForValue().set(
                    RedisKeys.HEALTH_SNAPSHOT, objectMapper.writeValueAsString(dto), TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[HealthClusterSnapshot] 발행 실패: {}", e.getMessage());
        }
    }

    /** 어느 노드든 호출 — 리더가 발행한 스냅샷 조회(없거나 실패 시 empty). */
    public Optional<Dto> read() {
        try {
            String json = redisTemplate.opsForValue().get(RedisKeys.HEALTH_SNAPSHOT);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, Dto.class));
        } catch (Exception e) {
            log.warn("[HealthClusterSnapshot] 조회 실패: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
