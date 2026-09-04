// [AGENT] 하트비트 watchdog — 등록된 하트비트 체크의 상태를 주기적으로 평가해
//   실패/복구를 health_check_event 로 적립한다. "조용히 멈춘" 잡을 여기서 잡는다.
package com.chs.springboot.global.monitor.health;

import com.chs.springboot.global.redis.LeaderElectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class HeartbeatWatchdog {

    private final HealthHeartbeat heartbeat;
    private final HealthCheckRecorder recorder;
    private final LeaderElectionService leaderElection;
    private final HealthClusterSnapshot clusterSnapshot;

    @Scheduled(fixedDelay = 10_000)
    public void evaluate() {
        for (HealthHeartbeat.Beat beat : heartbeat.snapshot()) {
            // UNKNOWN(아직 관측 없음)의 무시 여부는 recorder 가 판단한다
            record(beat);
        }
        // 리더만: 하트비트 원시상태 + 자원값을 클러스터 스냅샷으로 발행 → 비리더 보드가 노드 무관하게 읽음
        if (leaderElection.isLeader()) {
            clusterSnapshot.publish();
        }
    }

    // 키별 격리(InfraHealthEvaluator와 동일 패턴) — 한 체크의 기록 실패(DB 커넥션 등)가
    // 뒤 순서 체크의 평가까지 막지 않게. 2026-09-04: 격리가 없어 특정 체크의 반복 실패가
    // 그 뒤 체크들의 이력을 통째로 누락시켰을 가능성이 있는 사고를 겪음.
    private void record(HealthHeartbeat.Beat beat) {
        try {
            recorder.record(beat.checkKey(), beat.status(), beat.cause());
        } catch (Exception e) {
            log.warn("[HeartbeatWatchdog] {} 평가/기록 실패: {}", beat.checkKey(), e.getMessage());
        }
    }
}
