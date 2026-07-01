// [AGENT] HealthHeartbeat 빈 생성 + 하트비트 기반 체크의 임계 중앙 등록.
// 새 하트비트 체크를 계측할 때 여기 register 한 줄만 추가하면 watchdog·보드가 자동 반영.
package com.chs.springboot.global.monitor.health;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class HealthHeartbeatConfig {

    @Bean
    public HealthHeartbeat healthHeartbeat(Clock clock) {
        HealthHeartbeat heartbeat = new HealthHeartbeat(clock);
        // 임계는 대략 대상 주기의 2.5×(경고) / 5×(다운) grace 로 설정.
        register(heartbeat, HealthCheckCatalog.SCHED_OPENINTEREST_POLL, 150, 300);   // 60초
        register(heartbeat, HealthCheckCatalog.SCHED_TELEGRAM_POLL, 150, 300);       // 30초
        register(heartbeat, HealthCheckCatalog.SCHED_ANALYSIS, 180, 360);            // 60초
        register(heartbeat, HealthCheckCatalog.SCHED_WEATHER, 1500, 2100);           // 10분
        register(heartbeat, HealthCheckCatalog.SCHED_NEWS, 720, 1200);              // 5분
        register(heartbeat, HealthCheckCatalog.PIPE_ROLLUP_1S, 10, 30);             // 1초
        register(heartbeat, HealthCheckCatalog.PIPE_EMPTY_CANDLE_FIX, 720, 1200);   // 5분
        register(heartbeat, HealthCheckCatalog.PIPE_ROLLUP_1M, 180, 360);           // 1분
        register(heartbeat, HealthCheckCatalog.PIPE_ROLLUP_5M, 720, 1200);          // 5분
        register(heartbeat, HealthCheckCatalog.PIPE_AGGTRADE_FLUSH, 60, 180);       // 플러시 주기 가변 → 보수적
        register(heartbeat, HealthCheckCatalog.PIPE_S3_ARCHIVE, 1500, 2100);        // 10분
        register(heartbeat, HealthCheckCatalog.PIPE_KAFKA_CONSUMER, 60, 180);       // 유입 주기 가변 → 보수적(리더 전용)
        register(heartbeat, HealthCheckCatalog.SCHED_LEADER_ELECTION, 15, 30);      // 5초(전 노드 election 루프)
        return heartbeat;
    }

    private static void register(HealthHeartbeat heartbeat, HealthCheckCatalog check,
                                 long staleSeconds, long downSeconds) {
        heartbeat.register(check.key(), new HealthHeartbeat.Spec(staleSeconds, downSeconds));
    }
}
