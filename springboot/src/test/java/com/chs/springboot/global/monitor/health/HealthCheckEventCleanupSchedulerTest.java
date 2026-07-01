package com.chs.springboot.global.monitor.health;

import com.chs.springboot.global.redis.LeaderElectionService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HealthCheckEventCleanupSchedulerTest {

    private final HealthCheckEventRepository repository = mock(HealthCheckEventRepository.class);
    private final LeaderElectionService leaderElectionService = mock(LeaderElectionService.class);
    private final HealthCheckEventCleanupScheduler scheduler =
            new HealthCheckEventCleanupScheduler(repository, leaderElectionService);

    {
        ReflectionTestUtils.setField(scheduler, "retentionDays", 30);
    }

    @Test
    void leader_deletesExpiredRows() {
        when(leaderElectionService.isLeader()).thenReturn(true);

        scheduler.cleanup();

        verify(repository).deleteByLastFailedAtBefore(any(LocalDateTime.class));
    }

    @Test
    void nonLeader_doesNothing() {
        when(leaderElectionService.isLeader()).thenReturn(false);

        scheduler.cleanup();

        verify(repository, never()).deleteByLastFailedAtBefore(any());
    }
}
