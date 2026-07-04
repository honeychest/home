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

    // ── 판정+판정근거 묶음(Judged) — 표시와 기록이 같은 구현을 읽는다 ──

    private static final long GB = 1024L * 1024 * 1024;

    @Test
    void judgeRawTable_bundlesStatusAndDetail() {
        StatusLadder.Judged j = StatusLadder.judgeRawTable(6 * GB);
        assertThat(j.status()).isEqualTo(HealthStatus.DOWN);
        assertThat(j.detail()).isEqualTo("raw_agg_trade 6.0GB");
    }

    @Test
    void judgeRawTable_notCollected_isUnknown() {
        StatusLadder.Judged j = StatusLadder.judgeRawTable(-1);
        assertThat(j.status()).isEqualTo(HealthStatus.UNKNOWN);
        assertThat(j.detail()).isEqualTo("수집 기록 없음");
    }

    @Test
    void judgeWsConn_bundlesStatusAndDetail() {
        StatusLadder.Judged j = StatusLadder.judgeWsConn(300);
        assertThat(j.status()).isEqualTo(HealthStatus.DEGRADED);
        assertThat(j.detail()).isEqualTo("WS 세션 300개");
    }

    @Test
    void judgeWsConn_notCollected_isUnknown() {
        StatusLadder.Judged j = StatusLadder.judgeWsConn(-1);
        assertThat(j.status()).isEqualTo(HealthStatus.UNKNOWN);
        assertThat(j.detail()).isEqualTo("수집 기록 없음");
    }
}
