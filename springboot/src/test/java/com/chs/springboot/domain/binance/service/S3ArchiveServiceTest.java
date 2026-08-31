package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.repository.RawAggTradeRepository;
import com.chs.springboot.domain.binance.repository.S3ArchiveLogRepository;
import com.chs.springboot.global.monitor.service.MetricCollectorService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class S3ArchiveServiceTest {

    @Test
    void disabledConfigurationReturnsUnsuccessfulSkippedResultsWithoutSideEffects() {
        S3Client s3Client = mock(S3Client.class);
        RawAggTradeRepository rawAggTradeRepository = mock(RawAggTradeRepository.class);
        S3ArchiveLogRepository archiveLogRepository = mock(S3ArchiveLogRepository.class);
        MetricCollectorService metrics = mock(MetricCollectorService.class);
        S3ArchiveService service = new S3ArchiveService(
                s3Client, rawAggTradeRepository, archiveLogRepository, metrics);
        ReflectionTestUtils.setField(service, "archiveEnabled", false);

        S3ArchiveService.UploadResult upload = service.uploadAndLog(0L, 60_000L, "MANUAL");
        S3ArchiveService.ArchiveResult archive = service.archive(0L, 60_000L, "MANUAL");

        assertThat(upload.success()).isFalse();
        assertThat(upload.skipped()).isTrue();
        assertThat(upload.errorMessage()).contains("비활성화");
        assertThat(archive.success()).isFalse();
        assertThat(archive.skipped()).isTrue();
        assertThat(archive.errorMessage()).contains("비활성화");
        verifyNoInteractions(s3Client, rawAggTradeRepository, archiveLogRepository, metrics);
    }
}
