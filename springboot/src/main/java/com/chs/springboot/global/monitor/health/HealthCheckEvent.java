// [AGENT] 헬스 체크 실패 이력 엔티티 — 범용 checkKey 기반 (docs/health-check-board.md)
// 정상(OK)은 저장하지 않고, FAIL 전환 이벤트 + 원인 + 복구 시각만 적립한다.
package com.chs.springboot.global.monitor.health;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(
        name = "health_check_event",
        indexes = {
                @Index(name = "idx_hce_check_key", columnList = "check_key, last_failed_at"),
                @Index(name = "idx_hce_status", columnList = "status, last_failed_at")
        }
)
public class HealthCheckEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "check_key", nullable = false, length = 64)
    private String checkKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private HealthEventStatus status;

    @Column(name = "severity", length = 16)
    private String severity; // WARN / CRITICAL

    @Lob
    @Column(name = "cause")
    private String cause;

    @Column(name = "first_failed_at")
    private LocalDateTime firstFailedAt;

    @Column(name = "last_failed_at")
    private LocalDateTime lastFailedAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "source_env", nullable = false, length = 20)
    private String sourceEnv;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onPersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (sourceEnv == null || sourceEnv.isBlank()) {
            sourceEnv = "local";
        }
    }
}
