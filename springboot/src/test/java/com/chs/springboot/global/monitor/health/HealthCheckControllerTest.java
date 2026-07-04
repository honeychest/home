package com.chs.springboot.global.monitor.health;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthCheckControllerTest {

    private final HealthCheckService service = mock(HealthCheckService.class);
    private final HealthCheckController controller = new HealthCheckController(service);

    private static HealthCheckView view(HealthStatus status) {
        return new HealthCheckView("k", "라벨", "설명", "L1 인프라", "L1_INFRA", "치명",
                status, "상세", null, null, null, List.of());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summaryOf(List<HealthCheckView> checks) {
        when(service.getChecks()).thenReturn(checks);
        Map<String, Object> body = controller.checks().getBody();
        return (Map<String, Object>) body.get("summary");
    }

    @Test
    void summaryCountsByStatus() {
        Map<String, Object> summary = summaryOf(List.of(
                view(HealthStatus.UP), view(HealthStatus.UP),
                view(HealthStatus.DEGRADED), view(HealthStatus.DOWN), view(HealthStatus.UNKNOWN)));

        assertThat(summary.get("total")).isEqualTo(5);
        assertThat(summary.get("up")).isEqualTo(2);
        assertThat(summary.get("degraded")).isEqualTo(1);
        assertThat(summary.get("down")).isEqualTo(1);
        assertThat(summary.get("unknown")).isEqualTo(1);
        assertThat(summary.get("allOk")).isEqualTo(false);
    }

    // UNKNOWN(대기)은 이상이 아니므로 allOk 를 깨지 않는다 — 비리더 노드 오탐 방지와 같은 취지
    @Test
    void allOkWhenNoDownAndNoDegraded() {
        Map<String, Object> summary = summaryOf(List.of(
                view(HealthStatus.UP), view(HealthStatus.UNKNOWN)));

        assertThat(summary.get("allOk")).isEqualTo(true);
    }

    @Test
    void degradedAloneBreaksAllOk() {
        Map<String, Object> summary = summaryOf(List.of(
                view(HealthStatus.UP), view(HealthStatus.DEGRADED)));

        assertThat(summary.get("allOk")).isEqualTo(false);
    }
}
