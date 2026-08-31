package com.chs.springboot.global.monitor.health;

import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

class HealthHeartbeatConfigTest {

    // 하트비트 등록이 카탈로그 선언(source=HEARTBEAT + 임계)에서 그대로 파생되는지 회귀 고정.
    // 임계 자체는 카탈로그 각 항목이 자기 줄에 선언 — 선언↔등록 불일치는 구조적으로 불가능.
    @Test
    void heartbeatDeclarationsMatchRegistrations() {
        HealthHeartbeat heartbeat = new HealthHeartbeatConfig().healthHeartbeat(Clock.systemUTC());

        for (HealthCheckCatalog c : HealthCheckCatalog.all()) {
            assertThat(heartbeat.isRegistered(c.key()))
                    .as("체크 %s: 선언 source=%s", c.key(), c.source())
                    .isEqualTo(c.source() == HealthSource.HEARTBEAT);
        }
        assertThat(heartbeat.isRegistered("pipe-kafka-consumer")).isFalse();
        assertThat(heartbeat.isRegistered("pipe-aggtrade-flush")).isFalse();
    }
}
