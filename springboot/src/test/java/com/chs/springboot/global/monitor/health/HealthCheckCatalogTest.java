package com.chs.springboot.global.monitor.health;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HealthCheckCatalogTest {

    // 임계 문구 정책: EVENT(사용 시점 push, 임계 없음)만 null, 나머지 소스는 전부 기준 문구 제공.
    // 카탈로그 인터페이스 하나로 33개 항목의 표시 기준을 일괄 검증한다.
    @Test
    void thresholdTextPolicyCoversAllChecks() {
        for (HealthCheckCatalog c : HealthCheckCatalog.all()) {
            if (c.source() == HealthSource.EVENT) {
                assertThat(c.thresholdText()).as("체크 %s (EVENT 는 임계 없음)", c.key()).isNull();
            } else {
                assertThat(c.thresholdText()).as("체크 %s", c.key()).isNotBlank();
            }
        }
    }

    // 하트비트 임계 문구가 자기 줄의 선언값(stale/down 초)에서 파생되는지 표본 확인.
    @Test
    void heartbeatThresholdTextDerivesFromDeclaredSeconds() {
        assertThat(HealthCheckCatalog.SCHED_WEATHER.thresholdText())
                .contains("1500").contains("2100");
    }
}
