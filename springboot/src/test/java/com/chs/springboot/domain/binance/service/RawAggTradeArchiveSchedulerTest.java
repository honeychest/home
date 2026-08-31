package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.repository.RawAggTradeRepository;
import com.chs.springboot.global.monitor.service.MetricCollectorService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class RawAggTradeArchiveSchedulerTest {

    @Test
    void disabledConfigurationIsReportedAsDisabledAndSkipsRun() {
        RawAggTradeRepository repository = mock(RawAggTradeRepository.class);
        S3ArchiveService archiveService = mock(S3ArchiveService.class);
        MetricCollectorService metrics = mock(MetricCollectorService.class);
        RawAggTradeArchiveScheduler scheduler = new RawAggTradeArchiveScheduler(repository, archiveService, metrics);
        ReflectionTestUtils.setField(scheduler, "schedulingEnabled", true);
        ReflectionTestUtils.setField(scheduler, "archiveEnabled", false);

        scheduler.run();

        assertThat(scheduler.isDisabled()).isTrue();
        verifyNoInteractions(repository, archiveService, metrics);
    }
}
