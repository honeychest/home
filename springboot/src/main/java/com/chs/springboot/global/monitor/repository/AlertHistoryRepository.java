// [AGENT] AlertHistory JPA repository
package com.chs.springboot.global.monitor.repository;

import com.chs.springboot.global.monitor.entity.AlertHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface AlertHistoryRepository extends JpaRepository<AlertHistory, Long> {

    @Query("""
            select a
            from AlertHistory a
            where a.sourceEnv = :sourceEnv
              and (:from is null or a.sentAt >= :from)
              and (:to is null or a.sentAt <= :to)
              and (:type is null or a.metricType = :type)
            order by a.sentAt desc
            """)
    Page<AlertHistory> findByFilters(
            @Param("sourceEnv") String sourceEnv,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("type") AlertHistory.MetricType type,
            Pageable pageable
    );
}

