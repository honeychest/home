// [AGENT] 헬스 체크 보드 API — /api/admin/health/** (SecurityConfig 에서 ADMIN_ACCESS 자동 보호)
// GET /checks : 전체 체크 목록 + 상태 요약
// GET /events : 최근 실패 이력
package com.chs.springboot.global.monitor.health;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/health")
@RequiredArgsConstructor
public class HealthCheckController {

    private static final DateTimeFormatter GENERATED_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final HealthCheckService healthCheckService;

    @GetMapping("/checks")
    public ResponseEntity<Map<String, Object>> checks() {
        List<HealthCheckView> checks = healthCheckService.getChecks();

        Map<HealthStatus, Integer> counts = new EnumMap<>(HealthStatus.class);
        for (HealthStatus s : HealthStatus.values()) {
            counts.put(s, 0);
        }
        for (HealthCheckView v : checks) {
            counts.merge(v.status(), 1, Integer::sum);
        }

        Map<String, Object> summary = Map.of(
                "total", checks.size(),
                "up", counts.get(HealthStatus.UP),
                "degraded", counts.get(HealthStatus.DEGRADED),
                "down", counts.get(HealthStatus.DOWN),
                "unknown", counts.get(HealthStatus.UNKNOWN),
                "allOk", counts.get(HealthStatus.DOWN) == 0 && counts.get(HealthStatus.DEGRADED) == 0
        );

        return ResponseEntity.ok(Map.of(
                "generatedAt", LocalDateTime.now().format(GENERATED_TS),
                "summary", summary,
                "checks", checks
        ));
    }

    @GetMapping("/events")
    public ResponseEntity<List<HealthEventView>> events() {
        return ResponseEntity.ok(healthCheckService.getRecentEvents());
    }
}
