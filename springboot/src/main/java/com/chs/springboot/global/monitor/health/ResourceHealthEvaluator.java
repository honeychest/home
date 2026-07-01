// [AGENT] L7 리소스 계측 완성 — rawtable/ws 상태 전환을 감지해 health_check_event 로 적립.
// MetricCollectorService(leader에서 3초 스냅샷) 값 → HealthCheckRecorder(실패/복구 저장) 완결 루프.
// 5초마다 평가. 정상 지속 시 DB 쓰기 없음. 미수집(-1)=UNKNOWN 은 skip(비리더/초기).
package com.chs.springboot.global.monitor.health;

import com.chs.springboot.global.monitor.service.MetricCollectorService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ResourceHealthEvaluator {

    private final MetricCollectorService metricCollectorService;
    private final HealthCheckRecorder recorder;

    @Scheduled(fixedDelay = 5000)
    public void evaluate() {
        long bytes = metricCollectorService.getLastRawAggTradeBytes();
        record(HealthCheckCatalog.RES_RAWTABLE_GROWTH.key(),
                HealthCheckService.rawTableStatus(bytes),
                HealthCheckService.describeRawTable(bytes));

        int conns = metricCollectorService.getLastWsConnections();
        record(HealthCheckCatalog.RES_WS_CONNECTIONS.key(),
                HealthCheckService.wsConnStatus(conns),
                HealthCheckService.describeWsConn(conns));
    }

    private void record(String checkKey, HealthStatus status, String cause) {
        switch (status) {
            case DOWN -> recorder.markFail(checkKey, HealthStatus.DOWN, "CRITICAL", cause);
            case DEGRADED -> recorder.markFail(checkKey, HealthStatus.DEGRADED, "WARN", cause);
            case UP -> recorder.markOk(checkKey);
            case UNKNOWN -> {
                // 미수집(-1)/비리더 — 이력 남기지 않음(오탐 방지)
            }
        }
    }
}
