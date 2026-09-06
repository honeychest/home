// [AGENT] 헬스 체크 실패 이력 리포지토리
package com.chs.springboot.global.monitor.health;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface HealthCheckEventRepository extends JpaRepository<HealthCheckEvent, Long> {

    /** 현재 실행 환경의 최근 실패 이력 (최신순) */
    List<HealthCheckEvent> findTop100BySourceEnvOrderByLastFailedAtDesc(String sourceEnv);

    /** 현재 실행 환경에서 특정 체크의 가장 최근 이벤트 1건 */
    HealthCheckEvent findTopByCheckKeyAndSourceEnvOrderByLastFailedAtDesc(String checkKey, String sourceEnv);

    /** 현재 실행 환경에서 특정 체크의 최근 실패 이력(최신순 3건) — 팝오버 표시용 */
    List<HealthCheckEvent> findTop3ByCheckKeyAndSourceEnvOrderByLastFailedAtDesc(String checkKey, String sourceEnv);

    /** 현재 실행 환경에서 특정 체크의 아직 복구되지 않은(진행 중) 이벤트 1건 */
    HealthCheckEvent findTopByCheckKeyAndSourceEnvAndResolvedAtIsNullOrderByLastFailedAtDesc(
            String checkKey, String sourceEnv);

    /** 리테이션 정리 — 마지막 실패활동이 cutoff 이전인 이력 삭제(삭제 건수 반환) */
    long deleteByLastFailedAtBefore(LocalDateTime cutoff);
}
