// [AGENT] HealthHeartbeat 빈 생성 — 하트비트 임계는 카탈로그 각 항목이 자기 줄에 선언(단일 소스).
// 등록이 카탈로그에서 파생되므로 선언↔등록 불일치가 구조적으로 불가능하다(별도 검증 불필요).
// 새 하트비트 체크 추가 = 카탈로그 한 줄(임계 포함) + 대상 서비스의 beat/fail 계측.
package com.chs.springboot.global.monitor.health;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class HealthHeartbeatConfig {

    @Bean
    public HealthHeartbeat healthHeartbeat(Clock clock) {
        HealthHeartbeat heartbeat = new HealthHeartbeat(clock);
        for (HealthCheckCatalog c : HealthCheckCatalog.all()) {
            if (c.source() == HealthSource.HEARTBEAT) {
                heartbeat.register(c.key(), c.heartbeat());
            }
        }
        return heartbeat;
    }
}
