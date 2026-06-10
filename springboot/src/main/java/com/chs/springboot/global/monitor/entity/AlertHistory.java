// [AGENT] 모니터링 알림 이력 엔티티 (AlertService 저장용)
package com.chs.springboot.global.monitor.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(
        name = "alert_history",
        indexes = {
                @Index(name = "idx_alert_sent_at", columnList = "sent_at"),
                @Index(name = "idx_alert_metric_type", columnList = "metric_type, sent_at"),
                @Index(name = "idx_alert_source_env_sent_at", columnList = "source_env, sent_at")
        }
)
public class AlertHistory {

    public enum MetricType {
        CPU, RAM, DISK, REDIS_QUEUE, API_ERROR,
        FEED_BINANCE_TICKER, FEED_BINANCE_AGG, FEED_UPBIT
    }

    public enum Severity {
        WARN, CRITICAL
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "metric_type", nullable = false, length = 20)
    private MetricType metricType;

    @Column(name = "value", nullable = false)
    private Double value;

    @Column(name = "threshold", nullable = false)
    private Double threshold;

    @Column(name = "duration_sec", nullable = false)
    private Integer durationSec;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 10)
    private Severity severity;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;

    @Column(name = "source_env", nullable = false, length = 20)
    private String sourceEnv;

    @Column(name = "ack_at")
    private LocalDateTime ackAt;

    @Column(name = "resolve_at")
    private LocalDateTime resolveAt;

    @Lob
    @Column(name = "memo")
    private String memo;

    @PrePersist
    void onPersist() {
        if (sentAt == null) {
            sentAt = LocalDateTime.now();
        }
        if (sourceEnv == null || sourceEnv.isBlank()) {
            sourceEnv = "local";
        }
    }
}

