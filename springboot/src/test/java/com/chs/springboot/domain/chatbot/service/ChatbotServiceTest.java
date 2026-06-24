package com.chs.springboot.domain.chatbot.service;

import com.chs.springboot.domain.chatbot.config.ChatbotProperties;
import com.chs.springboot.domain.chatbot.dto.ChatCodexHealthResponse;
import com.chs.springboot.domain.chatbot.dto.ChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatbotServiceTest {

    @Mock
    private EvidenceRetriever evidenceRetriever;

    @Mock
    private GroundedAnswerGenerator answerGenerator;

    @Mock
    private CodexAnswerGenerator codexAnswerGenerator;

    @Mock
    private PageContextRegistry pageContextRegistry;

    @Mock
    private ChatbotLogService chatbotLogService;

    @Test
    @DisplayName("질문 답변은 근거 검색과 답변 생성을 조합해 응답한다")
    void ask_combinesEvidenceAndAnswer() {
        ChatbotService service = service();
        RetrievedEvidence evidence = new RetrievedEvidence(
                List.of(new Document("code", Map.of("source", "RedisConfig.java"))),
                List.of("RedisConfig.java")
        );
        when(evidenceRetriever.retrieve("레디스 키?", null)).thenReturn(evidence);
        when(answerGenerator.generate(any(), any(), any(), any())).thenReturn("근거 기반 답변");

        ChatResponse response = service.ask("레디스 키?", List.of(), null, "session-1", "LOCAL");

        assertThat(response.getAnswer()).isEqualTo("근거 기반 답변");
        assertThat(response.getSources()).containsExactly("RedisConfig.java");
        assertThat(response.getEffectiveModel()).isEqualTo("LOCAL");
    }

    @Test
    @DisplayName("오류가 나면 빈 근거와 오류 메시지를 반환한다")
    void ask_returnsErrorResponse() {
        ChatbotService service = service();
        when(evidenceRetriever.retrieve("질문", null)).thenThrow(new RuntimeException("vector down"));

        ChatResponse response = service.ask("질문", List.of(), null, "session-1", "LOCAL");

        assertThat(response.getAnswer()).isEqualTo("오류: vector down");
        assertThat(response.getSources()).isEmpty();
    }

    @Test
    @DisplayName("Codex external runner timeout 은 LOCAL 폴백과 timeout 사유로 응답한다")
    void ask_fallsBackToLocalWhenCodexTimeouts() {
        ChatbotService service = service();
        RetrievedEvidence evidence = new RetrievedEvidence(List.of(), List.of());
        when(evidenceRetriever.retrieve("질문", null)).thenReturn(evidence);
        when(codexAnswerGenerator.generate(any(), any(), any(), any(), any()))
                .thenReturn(CodexRunResult.failure(CodexRunStatus.TIMEOUT, "timeout"));
        when(answerGenerator.generate(any(), any(), any(), any())).thenReturn("LOCAL 답변");

        ChatResponse response = service.ask("질문", List.of(), null, "session-1", "CODEX");

        assertThat(response.getAnswer()).isEqualTo("LOCAL 답변");
        assertThat(response.getEffectiveModel()).isEqualTo("LOCAL");
        assertThat(response.getFallbackReason()).isEqualTo("timeout");
        assertThat(response.getNotice()).contains("응답 시간이 초과");
    }

    @Test
    @DisplayName("Codex 헬스체크는 external runner 에 Say OK only 요청 결과를 반환한다")
    void codexHealth_returnsExternalRunnerStatus() {
        ChatbotService service = service();
        when(codexAnswerGenerator.healthCheck()).thenReturn(CodexRunResult.success("OK", "", ""));

        ChatCodexHealthResponse response = service.codexHealth();

        assertThat(response.isHealthy()).isTrue();
        assertThat(response.getStatus()).isEqualTo("success");
        assertThat(response.getAnswer()).isEqualTo("OK");
    }

    private ChatbotService service() {
        return new ChatbotService(
                evidenceRetriever,
                answerGenerator,
                codexAnswerGenerator,
                pageContextRegistry,
                chatbotLogService,
                new ChatbotProperties()
        );
    }
}
