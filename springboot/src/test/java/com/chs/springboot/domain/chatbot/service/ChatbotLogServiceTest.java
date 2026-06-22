package com.chs.springboot.domain.chatbot.service;

import com.chs.springboot.domain.chatbot.model.ChatbotConversation;
import com.chs.springboot.domain.chatbot.model.ChatbotIssueType;
import com.chs.springboot.domain.chatbot.model.ChatbotRetrievedEvidence;
import com.chs.springboot.domain.chatbot.model.ChatbotTurn;
import com.chs.springboot.domain.chatbot.model.ChatbotTurnStatus;
import com.chs.springboot.domain.chatbot.repository.ChatbotAnalysisRepository;
import com.chs.springboot.domain.chatbot.repository.ChatbotConversationRepository;
import com.chs.springboot.domain.chatbot.repository.ChatbotRetrievedEvidenceRepository;
import com.chs.springboot.domain.chatbot.repository.ChatbotTurnRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatbotLogServiceTest {

    @Mock
    private ChatbotConversationRepository conversationRepository;

    @Mock
    private ChatbotTurnRepository turnRepository;

    @Mock
    private ChatbotRetrievedEvidenceRepository evidenceRepository;

    @Mock
    private ChatbotAnalysisRepository analysisRepository;

    private ChatbotLogService service;

    @BeforeEach
    void setUp() {
        service = new ChatbotLogService(
                conversationRepository,
                turnRepository,
                evidenceRepository,
                analysisRepository,
                new ObjectMapper(),
                "test"
        );
        lenient().when(conversationRepository.findFirstBySessionIdAndSourceEnvOrderByLastMessageAtDesc(anyString(), anyString()))
                .thenReturn(Optional.empty());
        lenient().when(conversationRepository.save(any(ChatbotConversation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(turnRepository.save(any(ChatbotTurn.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("로그 문자열 제한은 말줄임표를 포함해 DB 컬럼 길이를 넘지 않는다")
    void recordError_limitsQuestionWithinColumnLengthIncludingEllipsis() {
        String longQuestion = "가".repeat(2005);

        service.recordError("session-1", "signal", longQuestion, 123, "error");

        ArgumentCaptor<ChatbotTurn> captor = ArgumentCaptor.forClass(ChatbotTurn.class);
        verify(turnRepository).save(captor.capture());
        assertThat(captor.getValue().getQuestion())
                .hasSize(2000)
                .endsWith("...");
    }

    @Test
    @DisplayName("검색 근거 점수는 metadata가 아니라 Document score에서도 저장한다")
    void recordSuccess_savesDocumentScoreWhenMetadataHasNoScore() {
        Document document = Document.builder()
                .text("근거 본문")
                .metadata(Map.of("source", "A.java"))
                .score(0.42)
                .build();
        RetrievedEvidence evidence = new RetrievedEvidence(List.of(document), List.of("A.java"));

        service.recordSuccess("session-1", "signal", "질문", "답변", "검색", "LLM", "페이지", evidence, 123);

        ArgumentCaptor<ChatbotRetrievedEvidence> captor = ArgumentCaptor.forClass(ChatbotRetrievedEvidence.class);
        verify(evidenceRepository).save(captor.capture());
        assertThat(captor.getValue().getScore()).isEqualByComparingTo("0.42");
    }

    @Test
    @DisplayName("요약은 목록과 같은 필터를 repository에 전달한다")
    void summarize_passesSameFiltersAsTurnList() {
        LocalDateTime from = LocalDateTime.of(2026, 6, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 6, 22, 23, 59);
        when(turnRepository.countByFilters(
                from, to, "test", "signal", ChatbotIssueType.LATENCY, ChatbotTurnStatus.SUCCESS, 1000, "OI"
        )).thenReturn(3L);
        when(turnRepository.countSuspectedByFilters(
                from, to, "test", "signal", ChatbotIssueType.LATENCY, ChatbotTurnStatus.SUCCESS, 1000, "OI"
        )).thenReturn(2L);
        when(turnRepository.averageLatencyByFilters(
                from, to, "test", "signal", ChatbotIssueType.LATENCY, ChatbotTurnStatus.SUCCESS, 1000, "OI"
        )).thenReturn(1234.0);
        when(turnRepository.countSlowByFilters(
                from, to, "test", "signal", ChatbotIssueType.LATENCY, ChatbotTurnStatus.SUCCESS, 1000, "OI", 20_000
        )).thenReturn(1L);

        var response = service.summarize(
                from, to, " signal ", ChatbotIssueType.LATENCY, ChatbotTurnStatus.SUCCESS, 1000, " OI ");

        assertThat(response.totalLogs()).isEqualTo(3);
        assertThat(response.suspectedLogs()).isEqualTo(2);
        assertThat(response.averageLatencySeconds()).isEqualTo(1.2);
        assertThat(response.slowLogs()).isEqualTo(1);
    }
}
