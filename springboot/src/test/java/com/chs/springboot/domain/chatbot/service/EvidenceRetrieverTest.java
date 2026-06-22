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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EvidenceRetrieverTest {

    @Mock
    private VectorStore vectorStore;

    @Mock
    private PageContextRegistry pageContextRegistry;

    @Test
    @DisplayName("근거 검색은 source 메타데이터를 빈값 제거 + 중복 제거해서 노출하고 topK*배수로 과조회한다")
    void retrieve_deduplicatesSources_andOverFetches() {
        ChatbotProperties properties = new ChatbotProperties();
        properties.setTopK(3);
        properties.setOverFetchMultiplier(4); // 3*4=12 과조회
        EvidenceRetriever retriever = new EvidenceRetriever(vectorStore, properties, pageContextRegistry);

        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                new Document("a", Map.of("source", "A.java")),
                new Document("b", Map.of("source", "A.java")),
                new Document("c", Map.of("source", "")),
                new Document("d", Map.of())
        ));

        RetrievedEvidence evidence = retriever.retrieve("redis key", null);

        // 과조회 4건 중 상위 topK(3) 만 반환.
        assertThat(evidence.documentCount()).isEqualTo(3);
        assertThat(evidence.sources()).containsExactly("A.java");

        ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getTopK()).isEqualTo(12);
    }

    @Test
    @DisplayName("pageId 프리픽스에 속한 청크는 가산점으로 재정렬되어 근소차 전역 결과보다 앞선다(소프트 가중)")
    void retrieve_softBoostsCurrentPageChunks() {
        ChatbotProperties properties = new ChatbotProperties();
        properties.setTopK(1);
        properties.setOverFetchMultiplier(4);
        properties.setPageBoost(0.15);
        EvidenceRetriever retriever = new EvidenceRetriever(vectorStore, properties, pageContextRegistry);

        when(pageContextRegistry.find("analysis")).thenReturn(new PageContextRegistry.PageInfo(
                "분석", "분석 페이지", "analysis 분석", List.of("frontend/src/page/analysis")));

        // 전역(에러) 청크가 유사도는 더 높지만(0.60), 페이지 청크(0.55)는 +0.15=0.70 으로 역전된다.
        Document errorDoc = Document.builder()
                .text("error boilerplate").metadata(Map.of("source", "frontend/src/page/error/ErrorPage.tsx"))
                .score(0.60).build();
        Document pageDoc = Document.builder()
                .text("analysis page").metadata(Map.of("source", "frontend/src/page/analysis/AnalysisPage.tsx"))
                .score(0.55).build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(errorDoc, pageDoc));

        RetrievedEvidence evidence = retriever.retrieve("이게 뭐하는 페이지야", "analysis");

        assertThat(evidence.sources()).containsExactly("frontend/src/page/analysis/AnalysisPage.tsx");
    }

    @Test
    @DisplayName("페이지 매칭이 0건이어도 하드필터 없이 전역 결과는 그대로 살아있다")
    void retrieve_keepsGlobalResultsWhenNoPageMatch() {
        ChatbotProperties properties = new ChatbotProperties();
        properties.setTopK(2);
        EvidenceRetriever retriever = new EvidenceRetriever(vectorStore, properties, pageContextRegistry);

        lenient().when(pageContextRegistry.find("analysis")).thenReturn(new PageContextRegistry.PageInfo(
                "분석", "분석 페이지", "analysis 분석", List.of("frontend/src/page/analysis")));

        Document a = Document.builder().text("x").metadata(Map.of("source", "Other.java")).score(0.5).build();
        Document b = Document.builder().text("y").metadata(Map.of("source", "Another.java")).score(0.4).build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(a, b));

        RetrievedEvidence evidence = retriever.retrieve("질문", "analysis");

        assertThat(evidence.documentCount()).isEqualTo(2);
        assertThat(evidence.sources()).containsExactly("Other.java", "Another.java");
    }
}
