package com.chs.springboot.global.monitor.health;

import com.chs.springboot.global.redis.LeaderElectionService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

class InfraHealthEvaluatorTest {

    private final InfraHealthProbe probe = mock(InfraHealthProbe.class);
    private final HealthCheckRecorder recorder = mock(HealthCheckRecorder.class);
    private final LeaderElectionService leaderElection = mock(LeaderElectionService.class);
    private final InfraHealthEvaluator evaluator = new InfraHealthEvaluator(probe, recorder, leaderElection);

    private static final InfraHealthProbe.Probe UP = new InfraHealthProbe.Probe(HealthStatus.UP, "정상 응답");

    private void stubAllUp() {
        when(probe.mysql()).thenReturn(UP);
        when(probe.redis()).thenReturn(UP);
        when(probe.kafka()).thenReturn(UP);
        when(probe.postgres()).thenReturn(UP);
    }

    @Test
    void nonLeader_doesNothing() {
        when(leaderElection.isLeader()).thenReturn(false);

        evaluator.evaluate();

        verify(recorder, never()).record(anyString(), any(), any());
    }

    @Test
    void allUp_recordsUpForAllFour() {
        when(leaderElection.isLeader()).thenReturn(true);
        stubAllUp();

        evaluator.evaluate();

        verify(recorder).record(eq(HealthCheckCatalog.INFRA_MYSQL.key()), eq(HealthStatus.UP), anyString());
        verify(recorder).record(eq(HealthCheckCatalog.INFRA_REDIS.key()), eq(HealthStatus.UP), anyString());
        verify(recorder).record(eq(HealthCheckCatalog.INFRA_KAFKA.key()), eq(HealthStatus.UP), anyString());
        verify(recorder).record(eq(HealthCheckCatalog.INFRA_POSTGRES.key()), eq(HealthStatus.UP), anyString());
    }

    @Test
    void down_recordsDownWithDetail() {
        when(leaderElection.isLeader()).thenReturn(true);
        stubAllUp();
        when(probe.kafka()).thenReturn(new InfraHealthProbe.Probe(HealthStatus.DOWN, "브로커 노드 0개"));

        evaluator.evaluate();

        verify(recorder).record(HealthCheckCatalog.INFRA_KAFKA.key(),
                HealthStatus.DOWN, "브로커 노드 0개");
        verify(recorder).record(eq(HealthCheckCatalog.INFRA_MYSQL.key()), eq(HealthStatus.UP), anyString());
    }

    @Test
    void recorderFailureOnOneKey_doesNotStopOthers() {
        when(leaderElection.isLeader()).thenReturn(true);
        stubAllUp();
        // MySQL 다운 시나리오: 저장소(MySQL) 기록 자체가 실패해도 나머지 체크는 계속 평가된다.
        doThrow(new RuntimeException("DB 연결 실패"))
                .when(recorder).record(eq(HealthCheckCatalog.INFRA_MYSQL.key()), any(), any());

        evaluator.evaluate();

        verify(recorder).record(eq(HealthCheckCatalog.INFRA_REDIS.key()), eq(HealthStatus.UP), anyString());
        verify(recorder).record(eq(HealthCheckCatalog.INFRA_KAFKA.key()), eq(HealthStatus.UP), anyString());
        verify(recorder).record(eq(HealthCheckCatalog.INFRA_POSTGRES.key()), eq(HealthStatus.UP), anyString());
    }
}
