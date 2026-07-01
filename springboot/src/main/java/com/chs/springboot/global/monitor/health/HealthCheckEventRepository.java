// [AGENT] 헬스 체크 실패 이력 리포지토리
package com.chs.springboot.global.monitor.health;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HealthCheckEventRepository extends JpaRepository<HealthCheckEvent, Long> {

    /** 최근 실패 이력 (전체, 최신순) */
    List<HealthCheckEvent> findTop100ByOrderByLastFailedAtDesc();

    /** 특정 체크의 가장 최근 이벤트 1건 */
    HealthCheckEvent findTopByCheckKeyOrderByLastFailedAtDesc(String checkKey);

    /** 특정 체크의 최근 실패 이력(최신순 3건) — 팝오버 표시용 */
    List<HealthCheckEvent> findTop3ByCheckKeyOrderByLastFailedAtDesc(String checkKey);

    /** 특정 체크의 아직 복구되지 않은(진행 중) 이벤트 1건 */
    HealthCheckEvent findTopByCheckKeyAndResolvedAtIsNullOrderByLastFailedAtDesc(String checkKey);
}
