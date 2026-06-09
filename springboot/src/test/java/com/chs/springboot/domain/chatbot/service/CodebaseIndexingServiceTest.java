package com.chs.springboot.domain.chatbot.service;

import com.chs.springboot.domain.chatbot.dto.ReindexJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@ExtendWith(MockitoExtension.class)
class CodebaseIndexingServiceTest {

    @Mock
    private AsyncReindexRunner runner;

    @Test
    @DisplayName("재색인은 동시에 하나만 실행하고 완료 콜백 후 다시 시작 가능")
    void startReindex_locksUntilRunnerCompletes() {
        AtomicReference<Runnable> onCompleteRef = new AtomicReference<>();
        doAnswer(invocation -> {
            onCompleteRef.set(invocation.getArgument(1));
            return null;
        }).when(runner).run(any(ReindexJob.class), any(Runnable.class));

        CodebaseIndexingService service = new CodebaseIndexingService(runner);

        ReindexJob first = service.startReindex();

        assertThat(first.getStatus()).isEqualTo("RUNNING");
        assertThat(service.getJob(first.getJobId())).isSameAs(first);
        assertThatThrownBy(service::startReindex)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("reindex already running");

        onCompleteRef.get().run();

        ReindexJob second = service.startReindex();
        assertThat(second.getJobId()).isNotEqualTo(first.getJobId());
    }
}
