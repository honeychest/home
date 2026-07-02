package com.chs.springboot.global.monitor.health;

import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

class HealthHeartbeatConfigTest {

    // 카탈로그 선언(source=HEARTBEAT) ↔ Config 임계 등록의 양방향 일치.
    // 어긋나면 빈 생성 자체가 IllegalStateException — 이 테스트가 기동 실패를 먼저 잡는다.
    @Test
    void heartbeatDeclarationsMatchRegistrations() {
        HealthHeartbeat heartbeat = new HealthHeartbeatConfig().healthHeartbeat(Clock.systemUTC());

        for (HealthCheckCatalog c : HealthCheckCatalog.all()) {
            assertThat(heartbeat.isRegistered(c.key()))
                    .as("체크 %s: 선언 source=%s", c.key(), c.source())
                    .isEqualTo(c.source() == HealthSource.HEARTBEAT);
        }
    }
}
