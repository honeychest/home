package com.chs.springboot.global.monitor.health;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StatusLadderTest {

    private final StatusLadder ladder = new StatusLadder(10, 30);

    @Test
    void underDegradedLine_isUp() {
        assertThat(ladder.judge(9)).isEqualTo(HealthStatus.UP);
    }

    @Test
    void atDegradedLine_isDegraded() {
        assertThat(ladder.judge(10)).isEqualTo(HealthStatus.DEGRADED);
        assertThat(ladder.judge(29)).isEqualTo(HealthStatus.DEGRADED);
    }

    @Test
    void atDownLine_isDown() {
        assertThat(ladder.judge(30)).isEqualTo(HealthStatus.DOWN);
    }

    @Test
    void negativeSentinel_isUnknown() {
        assertThat(ladder.judgeOrUnknown(-1)).isEqualTo(HealthStatus.UNKNOWN);
        assertThat(ladder.judgeOrUnknown(0)).isEqualTo(HealthStatus.UP);
    }

    @Test
    void text_formatsThresholdsWithUnit() {
        assertThat(StatusLadder.FEED_SECONDS.text("초")).isEqualTo("경고 ≥10초 · 다운 ≥30초");
    }

    @Test
    void text_appendsNoteVerbatim() {
        assertThat(StatusLadder.RAWTABLE_GB.text("GB", " (data+index)"))
                .isEqualTo("경고 ≥3GB · 다운 ≥6GB (data+index)");
        assertThat(StatusLadder.RESOURCE_PCT.text("%", "(AlertService 임계와 동일)"))
                .isEqualTo("경고 ≥70% · 다운 ≥80%(AlertService 임계와 동일)");
    }
}
