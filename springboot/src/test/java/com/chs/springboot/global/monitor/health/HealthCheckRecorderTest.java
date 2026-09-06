package com.chs.springboot.global.monitor.health;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HealthCheckRecorderTest {

    private static final String KEY = "infra-mysql";
    private static final String LOCAL = "local";

    private final HealthCheckEventRepository repository = mock(HealthCheckEventRepository.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final HealthCheckRecorder recorder = new HealthCheckRecorder(repository, publisher);

    private HealthCheckEvent openWith(HealthEventStatus status) {
        HealthCheckEvent e = new HealthCheckEvent();
        e.setCheckKey(KEY);
        e.setStatus(status);
        return e;
    }

    @Test
    void newFailure_publishesTransition() {
        when(repository.findTopByCheckKeyAndSourceEnvAndResolvedAtIsNullOrderByLastFailedAtDesc(KEY, LOCAL))
                .thenReturn(null);

        recorder.record(KEY, HealthStatus.DOWN, "연결 실패");

        ArgumentCaptor<HealthCheckTransitionEvent> captor = ArgumentCaptor.forClass(HealthCheckTransitionEvent.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(HealthStatus.DOWN);
        assertThat(captor.getValue().recovery()).isFalse();
    }

    @Test
    void down_derivesCriticalSeverity() {
        when(repository.findTopByCheckKeyAndSourceEnvAndResolvedAtIsNullOrderByLastFailedAtDesc(KEY, LOCAL))
                .thenReturn(null);

        recorder.record(KEY, HealthStatus.DOWN, "연결 실패");

        ArgumentCaptor<HealthCheckEvent> captor = ArgumentCaptor.forClass(HealthCheckEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getSeverity()).isEqualTo("CRITICAL");
    }

    @Test
    void degraded_derivesWarnSeverity() {
        when(repository.findTopByCheckKeyAndSourceEnvAndResolvedAtIsNullOrderByLastFailedAtDesc(KEY, LOCAL))
                .thenReturn(null);

        recorder.record(KEY, HealthStatus.DEGRADED, "지연");

        ArgumentCaptor<HealthCheckEvent> captor = ArgumentCaptor.forClass(HealthCheckEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getSeverity()).isEqualTo("WARN");
    }

    @Test
    void unknown_recordsNothing() {
        recorder.record(KEY, HealthStatus.UNKNOWN, null);

        verify(repository, never()).save(any());
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void up_closesOpenEventLikeMarkOk() {
        when(repository.findTopByCheckKeyAndSourceEnvAndResolvedAtIsNullOrderByLastFailedAtDesc(KEY, LOCAL))
                .thenReturn(openWith(HealthEventStatus.DOWN));

        recorder.record(KEY, HealthStatus.UP, null);

        ArgumentCaptor<HealthCheckTransitionEvent> captor = ArgumentCaptor.forClass(HealthCheckTransitionEvent.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue().recovery()).isTrue();
    }

    @Test
    void repeatedSameStatus_doesNotPublish() {
        when(repository.findTopByCheckKeyAndSourceEnvAndResolvedAtIsNullOrderByLastFailedAtDesc(KEY, LOCAL))
                .thenReturn(openWith(HealthEventStatus.DOWN)); // 이미 DOWN 진행 중

        recorder.record(KEY, HealthStatus.DOWN, "연결 실패");

        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void escalation_publishes() {
        when(repository.findTopByCheckKeyAndSourceEnvAndResolvedAtIsNullOrderByLastFailedAtDesc(KEY, LOCAL))
                .thenReturn(openWith(HealthEventStatus.DEGRADED)); // 경고 → 다운 악화

        recorder.record(KEY, HealthStatus.DOWN, "악화");

        verify(publisher).publishEvent(any(HealthCheckTransitionEvent.class));
    }

    @Test
    void recovery_publishesWithPreviousStatus() {
        when(repository.findTopByCheckKeyAndSourceEnvAndResolvedAtIsNullOrderByLastFailedAtDesc(KEY, LOCAL))
                .thenReturn(openWith(HealthEventStatus.DOWN));

        recorder.markOk(KEY);

        ArgumentCaptor<HealthCheckTransitionEvent> captor = ArgumentCaptor.forClass(HealthCheckTransitionEvent.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue().recovery()).isTrue();
        assertThat(captor.getValue().status()).isEqualTo(HealthStatus.DOWN);
    }

    @Test
    void markOkWithNoOpenEvent_doesNothing() {
        when(repository.findTopByCheckKeyAndSourceEnvAndResolvedAtIsNullOrderByLastFailedAtDesc(KEY, LOCAL))
                .thenReturn(null);

        recorder.markOk(KEY);

        verify(repository, never()).save(any());
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void prodRecoveryDoesNotCloseLocalEvent() {
        ReflectionTestUtils.setField(recorder, "sourceEnv", "prod");
        when(repository.findTopByCheckKeyAndSourceEnvAndResolvedAtIsNullOrderByLastFailedAtDesc(KEY, "prod"))
                .thenReturn(null);

        recorder.markOk(KEY);

        verify(repository).findTopByCheckKeyAndSourceEnvAndResolvedAtIsNullOrderByLastFailedAtDesc(KEY, "prod");
        verify(repository, never()).save(any());
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void newFailureStoresNormalizedSourceEnv() {
        ReflectionTestUtils.setField(recorder, "sourceEnv", "prod");
        when(repository.findTopByCheckKeyAndSourceEnvAndResolvedAtIsNullOrderByLastFailedAtDesc(KEY, "prod"))
                .thenReturn(null);

        recorder.record(KEY, HealthStatus.DOWN, "연결 실패");

        ArgumentCaptor<HealthCheckEvent> captor = ArgumentCaptor.forClass(HealthCheckEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getSourceEnv()).isEqualTo("prod");
    }
}
