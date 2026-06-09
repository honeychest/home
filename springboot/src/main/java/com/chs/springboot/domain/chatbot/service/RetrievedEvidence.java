// [AGENT] 역할: RAG 근거 검색 결과 값 | 연관파일: EvidenceRetriever.java, ChatbotService.java
package com.chs.springboot.domain.chatbot.service;

import org.springframework.ai.document.Document;

import java.util.List;

public record RetrievedEvidence(List<Document> documents, List<String> sources) {
    public int documentCount() {
        return documents.size();
    }
}
