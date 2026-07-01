// [AGENT] 역할: raw_agg_trade 아카이빙 스케줄러 | 연관파일: S3ArchiveService.java, MetricCollectorService.java, RawAggTradeRepository.java
// 실행 조건: CPU < archive.cpu-max-percent (진입 70%, 배치 중 90% 초과 시 중단)
// 보존 기간: archive.retention-days 이전 데이터를 하루 단위로 S3 아카이빙
package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.repository.RawAggTradeRepository;
import com.chs.springboot.global.monitor.health.HealthCheckCatalog;
import com.chs.springboot.global.monitor.health.HealthHeartbeat;
import com.chs.springboot.global.monitor.service.MetricCollectorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

@Slf4j
@Service
public class RawAggTradeArchiveScheduler {

    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    private static final String HEALTH_KEY = HealthCheckCatalog.PIPE_S3_ARCHIVE.key();

    private final RawAggTradeRepository rawAggTradeRepository;
    private final S3ArchiveService s3ArchiveService;
    private final MetricCollectorService metricCollectorService;
    private final HealthHeartbeat healthHeartbeat;

    @Value("${spring.task.scheduling.enabled:true}")
    private boolean schedulingEnabled;

    @Value("${archive.retention-days:3}")
    private int retentionDays;

    @Value("${archive.cpu-max-percent:70.0}")
    private double cpuMaxPercent;

    private int consecutiveFailures = 0;
    private boolean disabled = false;
    private String lastFailureMessage;

    public RawAggTradeArchiveScheduler(RawAggTradeRepository rawAggTradeRepository,
                                       S3ArchiveService s3ArchiveService,
                                       MetricCollectorService metricCollectorService,
                                       HealthHeartbeat healthHeartbeat) {
        this.rawAggTradeRepository = rawAggTradeRepository;
        this.s3ArchiveService = s3ArchiveService;
        this.metricCollectorService = metricCollectorService;
        this.healthHeartbeat = healthHeartbeat;
    }

    /**
     * 10분마다 실행. 아래 조건 충족 시 하루치 데이터 아카이빙 수행.
     * - CPU < cpuMaxPercent (70%)
     * - 보존 기간(retentionDays) 이전 데이터 존재
     *
     * initialDelay: 서버 기동 안정화 후 1분 뒤 첫 실행
     */
    @Scheduled(fixedDelay = 600_000L, initialDelay = 60_000L)
    public void run() {
        if (!schedulingEnabled) return;

        if (disabled) {
            log.warn("[Archive] S3 연속 {}회 실패로 비활성화 상태 — 스킵 (마지막 에러: {})",
                    MAX_CONSECUTIVE_FAILURES, lastFailureMessage);
            healthHeartbeat.fail(HEALTH_KEY, "연속 " + MAX_CONSECUTIVE_FAILURES + "회 실패로 비활성화: " + lastFailureMessage);
            return;
        }

        double cpu = metricCollectorService.getLastCpu();
        if (cpu >= 0 && cpu >= cpuMaxPercent) {
            log.warn("[Archive] CPU={}% 초과 — 스킵 (임계값={}%)", String.format("%.1f", cpu), cpuMaxPercent);
            healthHeartbeat.beat(HEALTH_KEY); // 의도된 throttle — 정상
            return;
        }

        long retentionMs = (long) retentionDays * 24 * 60 * 60 * 1_000;
        long cutoffMs = System.currentTimeMillis() - retentionMs;

        Long minTradedAt = rawAggTradeRepository.findMinTradedAtBefore(cutoffMs);
        if (minTradedAt == null) {
            log.warn("[Archive] 아카이빙 대상 없음 (보존기간 {}일)", retentionDays);
            healthHeartbeat.beat(HEALTH_KEY); // 대상 없음 — 정상
            return;
        }

        LocalDate date = Instant.ofEpochMilli(minTradedAt)
                .atZone(ZoneOffset.UTC)
                .toLocalDate();

        long startMs = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        long endMs = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();

        log.warn("[Archive] 시작: date={} cpu={}%", date, String.format("%.1f", cpu));
        S3ArchiveService.ArchiveResult result = s3ArchiveService.archive(startMs, endMs, "SCHEDULER");

        if (result.success()) {
            consecutiveFailures = 0;
            healthHeartbeat.beat(HEALTH_KEY);
        } else {
            consecutiveFailures++;
            lastFailureMessage = result.errorMessage();
            healthHeartbeat.fail(HEALTH_KEY, lastFailureMessage);
            log.error("[Archive] 실패 ({}/{}): {}", consecutiveFailures, MAX_CONSECUTIVE_FAILURES, lastFailureMessage);
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                disabled = true;
                log.error("[Archive] 연속 {}회 실패 — 스케줄러 비활성화. POST /api/admin/archive/scheduler-reset 으로 복구 필요",
                        MAX_CONSECUTIVE_FAILURES);
            }
        }
    }

    public void reset() {
        this.consecutiveFailures = 0;
        this.disabled = false;
        this.lastFailureMessage = null;
        log.warn("[Archive] 스케줄러 수동 리셋 완료 — 다음 주기부터 재시도");
    }

    public boolean isDisabled() {
        return disabled;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public String getLastFailureMessage() {
        return lastFailureMessage;
    }
}
