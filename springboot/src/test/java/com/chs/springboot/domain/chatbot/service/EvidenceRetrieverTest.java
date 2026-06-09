package com.chs.springboot.domain.chatbot.service;

import com.chs.springboot.domain.chatbot.config.ChatbotProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EvidenceRetrieverTest {

    @Mock
    private VectorStore vectorStore;

    @Test
    @DisplayName("근거 검색은 source 메타데이터를 빈값 제거 + 중복 제거해서 노출")
    void retrieve_deduplicatesSources() {
        ChatbotProperties properties = new ChatbotProperties();
        properties.setTopK(3);
        EvidenceRetriever retriever = new EvidenceRetriever(vectorStore, properties);

        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                new Document("a", Map.of("source", "A.java")),
                new Document("b", Map.of("source", "A.java")),
                new Document("c", Map.of("source", "")),
                new Document("d", Map.of())
        ));

        RetrievedEvidence evidence = retriever.retrieve("redis key");

        assertThat(evidence.documentCount()).isEqualTo(4);
        assertThat(evidence.sources()).containsExactly("A.java");

        ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getTopK()).isEqualTo(3);
    }
}
