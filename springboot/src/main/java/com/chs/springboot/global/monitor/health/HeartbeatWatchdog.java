// [AGENT] 하트비트 watchdog — 등록된 하트비트 체크의 상태를 주기적으로 평가해
//   실패/복구를 health_check_event 로 적립한다. "조용히 멈춘" 잡을 여기서 잡는다.
package com.chs.springboot.global.monitor.health;

import com.chs.springboot.global.redis.LeaderElectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
            recorder.record(beat.checkKey(), beat.status(), beat.cause());
        }
        // 리더만: 하트비트 원시상태 + 자원값을 클러스터 스냅샷으로 발행 → 비리더 보드가 노드 무관하게 읽음
        if (leaderElection.isLeader()) {
            clusterSnapshot.publish();
        }
    }
}
