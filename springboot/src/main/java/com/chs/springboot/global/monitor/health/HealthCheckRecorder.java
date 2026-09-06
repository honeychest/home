// [AGENT] 헬스 체크 실패/복구 기록 공용 API — 모든 카테고리가 이 recorder로 계측한다.
// 원칙: 정상은 저장하지 않고, FAIL 전환/지속과 복구(RESOLVED)만 health_check_event 에 적립.
//  - record : 판정 상태를 그대로 넘기는 단일 입구. 심각도는 상태에서 파생(DOWN=CRITICAL, DEGRADED=WARN),
//             UP=복구 처리, UNKNOWN(미수집/미관측)=무동작(오탐 방지).
//  - markOk : 진행 중 이벤트가 있으면 resolvedAt 을 찍어 닫는다 (없으면 무동작)
package com.chs.springboot.global.monitor.health;

import com.chs.springboot.global.monitor.SourceEnvNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class HealthCheckRecorder {

    private final HealthCheckEventRepository repository;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${monitor.alert-history.source-env:${spring.profiles.active:local}}")
    private String sourceEnv;

    /** 판정 상태를 그대로 기록 — 심각도는 상태에서 파생. 모든 계측 지점의 단일 입구. */
    @Transactional
    public void record(String checkKey, HealthStatus status, String cause) {
        switch (status) {
            case DOWN -> markFail(checkKey, HealthStatus.DOWN, "CRITICAL", cause);
            case DEGRADED -> markFail(checkKey, HealthStatus.DEGRADED, "WARN", cause);
            case UP -> markOk(checkKey);
            case UNKNOWN -> {
                // 미수집/미관측 — 이력 남기지 않음(오탐 방지)
            }
        }
    }

    /** 실패/경고 상태를 기록. status 는 DOWN 또는 DEGRADED. record 를 통해서만 진입한다. */
    private void markFail(String checkKey, HealthStatus status, String severity, String cause) {
        HealthEventStatus eventStatus = HealthEventStatus.fromFailure(status);
        LocalDateTime now = LocalDateTime.now();
        String currentSourceEnv = normalizedSourceEnv();
        HealthCheckEvent open = repository.findTopByCheckKeyAndSourceEnvAndResolvedAtIsNullOrderByLastFailedAtDesc(
                checkKey, currentSourceEnv);
        HealthEventStatus previousStatus = open == null ? null : open.getStatus();
        if (open == null) {
            open = new HealthCheckEvent();
            open.setCheckKey(checkKey);
            open.setFirstFailedAt(now);
            open.setCreatedAt(now);
            open.setSourceEnv(currentSourceEnv);
        }
        open.setStatus(eventStatus);
        open.setSeverity(severity);
        open.setCause(cause);
        open.setLastFailedAt(now);
        open.setUpdatedAt(now);
        repository.save(open);

        // 전이(신규 open 또는 상태 변화) 순간에만 알림 이벤트 발행 → 반복 실패로 인한 도배 방지
        if (previousStatus != eventStatus) {
            eventPublisher.publishEvent(new HealthCheckTransitionEvent(checkKey, status, cause, false));
        }
    }

    /** 정상 복구를 기록. 진행 중 이벤트가 있으면 닫는다. */
    @Transactional
    public void markOk(String checkKey) {
        String currentSourceEnv = normalizedSourceEnv();
        HealthCheckEvent open = repository.findTopByCheckKeyAndSourceEnvAndResolvedAtIsNullOrderByLastFailedAtDesc(
                checkKey, currentSourceEnv);
        if (open == null) {
            return;
        }
        HealthStatus recovered = open.getStatus().asHealthStatus(); // 닫히기 직전 상태(DOWN/DEGRADED)
        LocalDateTime now = LocalDateTime.now();
        open.setStatus(HealthEventStatus.RESOLVED);
        open.setResolvedAt(now);
        open.setUpdatedAt(now);
        repository.save(open);

        // 복구 이벤트 발행(직전 상태를 담아 notifier가 DOWN 복구만 알리도록)
        eventPublisher.publishEvent(new HealthCheckTransitionEvent(checkKey, recovered, null, true));
    }

    private String normalizedSourceEnv() {
        return SourceEnvNormalizer.normalize(sourceEnv);
    }
}
