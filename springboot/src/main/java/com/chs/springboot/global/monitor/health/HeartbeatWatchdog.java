// [AGENT] 하트비트 watchdog — 등록된 하트비트 체크의 상태를 주기적으로 평가해
//   실패/복구를 health_check_event 로 적립한다. "조용히 멈춘" 잡을 여기서 잡는다.
package com.chs.springboot.global.monitor.health;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HeartbeatWatchdog {

    private final HealthHeartbeat heartbeat;
    private final HealthCheckRecorder recorder;

    @Scheduled(fixedDelay = 10_000)
    public void evaluate() {
        for (HealthHeartbeat.Beat beat : heartbeat.snapshot()) {
            switch (beat.status()) {
                case DOWN -> recorder.markFail(beat.checkKey(), HealthStatus.DOWN, "CRITICAL", beat.cause());
                case DEGRADED -> recorder.markFail(beat.checkKey(), HealthStatus.DEGRADED, "WARN", beat.cause());
                case UP -> recorder.markOk(beat.checkKey());
                case UNKNOWN -> {
                    // 아직 관측 없음(대기) — 이력 남기지 않음
                }
            }
        }
    }
}
