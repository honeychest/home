// [AGENT] 역할: 질문과 관련된 코드베이스 근거 검색 Adapter | 연관파일: ChatbotService.java
package com.chs.springboot.domain.chatbot.service;

import com.chs.springboot.domain.chatbot.config.ChatbotProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class EvidenceRetriever {

    private final VectorStore vectorStore;
    private final ChatbotProperties properties;

    public EvidenceRetriever(VectorStore vectorStore, ChatbotProperties properties) {
        this.vectorStore = vectorStore;
        this.properties = properties;
    }

    public RetrievedEvidence retrieve(String question) {
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder().query(question).topK(properties.getTopK()).build()
        );
        List<String> sources = docs.stream()
                .map(doc -> (String) doc.getMetadata().get("source"))
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .collect(Collectors.toList());
        return new RetrievedEvidence(docs, sources);
    }
}
