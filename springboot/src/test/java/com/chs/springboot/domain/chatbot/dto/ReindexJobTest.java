package com.chs.springboot.domain.chatbot.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReindexJobTest {

    @Test
    @DisplayName("작업 상태 전이를 상태조회 응답으로 변환한다")
    void toStatusResponse_mapsProgressAndError() {
        ReindexJob job = new ReindexJob("job-1");
        job.setTotalChunks(10);
        job.setProcessedChunks(4);

        ReindexStatusResponse running = job.toStatusResponse();
        assertThat(running.status()).isEqualTo("RUNNING");
        assertThat(running.processedChunks()).isEqualTo(4);
        assertThat(running.totalChunks()).isEqualTo(10);
        assertThat(running.error()).isEmpty();

        job.markFailed("boom");

        ReindexStatusResponse failed = job.toStatusResponse();
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.error()).isEqualTo("boom");
    }
}
