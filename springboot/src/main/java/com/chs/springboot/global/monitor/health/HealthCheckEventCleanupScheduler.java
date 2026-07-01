// [AGENT] health_check_event 리테이션 정리 — retention-days(기본 30일) 경과 이력 삭제.
// 로그 테이블 무한 적재 방지. 매일 1회, leader 노드에서만 실행(중복 삭제 방지).
// 진행 중(미복구) 장애는 lastFailedAt 이 계속 갱신되므로 활성 동안엔 삭제되지 않는다.
package com.chs.springboot.global.monitor.health;

import com.chs.springboot.global.redis.LeaderElectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class HealthCheckEventCleanupScheduler {

    private final HealthCheckEventRepository repository;
    private final LeaderElectionService leaderElectionService;

    @Value("${monitor.health.retention-days:30}")
    private int retentionDays;

    @Scheduled(cron = "0 30 4 * * *")
    @Transactional
    public void cleanup() {
        if (!leaderElectionService.isLeader()) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        long deleted = repository.deleteByLastFailedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("[HealthCleanup] {}일 경과 헬스 이력 {}건 삭제", retentionDays, deleted);
        }
    }
}
