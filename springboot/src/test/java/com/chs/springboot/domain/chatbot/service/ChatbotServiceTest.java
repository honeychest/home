package com.chs.springboot.domain.chatbot.service;

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
    private PageContextRegistry pageContextRegistry;

    @Mock
    private ChatbotLogService chatbotLogService;

    @Test
    @DisplayName("질문 답변은 근거 검색과 답변 생성을 조합해 응답한다")
    void ask_combinesEvidenceAndAnswer() {
        ChatbotService service = new ChatbotService(
                evidenceRetriever,
                answerGenerator,
                pageContextRegistry,
                chatbotLogService
        );
        RetrievedEvidence evidence = new RetrievedEvidence(
                List.of(new Document("code", Map.of("source", "RedisConfig.java"))),
                List.of("RedisConfig.java")
        );
        when(evidenceRetriever.retrieve("레디스 키?", null)).thenReturn(evidence);
        when(answerGenerator.generate(any(), any(), any(), any())).thenReturn("근거 기반 답변");

        ChatResponse response = service.ask("레디스 키?", List.of(), null, "session-1");

        assertThat(response.getAnswer()).isEqualTo("근거 기반 답변");
        assertThat(response.getSources()).containsExactly("RedisConfig.java");
    }

    @Test
    @DisplayName("오류가 나면 빈 근거와 오류 메시지를 반환한다")
    void ask_returnsErrorResponse() {
        ChatbotService service = new ChatbotService(
                evidenceRetriever,
                answerGenerator,
                pageContextRegistry,
                chatbotLogService
        );
        when(evidenceRetriever.retrieve("질문", null)).thenThrow(new RuntimeException("vector down"));

        ChatResponse response = service.ask("질문", List.of(), null, "session-1");

        assertThat(response.getAnswer()).isEqualTo("오류: vector down");
        assertThat(response.getSources()).isEmpty();
    }
}
