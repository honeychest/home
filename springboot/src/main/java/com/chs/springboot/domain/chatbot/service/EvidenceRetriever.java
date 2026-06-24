// [AGENT] 역할: 질문과 관련된 코드베이스 근거 검색 Adapter | 연관파일: ChatbotService.java, PageContextRegistry.java
package com.chs.springboot.domain.chatbot.service;

import com.chs.springboot.domain.chatbot.config.ChatbotProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class EvidenceRetriever {

    private final VectorStore vectorStore;
    private final ChatbotProperties properties;
    private final PageContextRegistry pageContextRegistry;

    public EvidenceRetriever(VectorStore vectorStore, ChatbotProperties properties,
                             PageContextRegistry pageContextRegistry) {
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.pageContextRegistry = pageContextRegistry;
    }

    /**
     * 질문 근거를 검색한다. pageId 가 주어지면 해당 페이지 경로 프리픽스에 속한 청크에 가산점을 줘
     * 재정렬하는 '소프트 가중'을 적용한다(하드 필터 아님 — 매칭 0건이어도 전역 결과는 그대로 유지).
     * topK 개만 뽑지 않고 topK*overFetchMultiplier 만큼 과조회한 뒤 재정렬해 상위 topK 만 반환.
     */
    public RetrievedEvidence retrieve(String question, String pageId) {
        int topK = properties.getTopK();
        int overFetch = Math.max(topK, topK * Math.max(1, properties.getOverFetchMultiplier()));

        List<Document> fetched = vectorStore.similaritySearch(
                SearchRequest.builder().query(question).topK(overFetch).build());
        if (fetched == null) {
            fetched = List.of();
        }

        List<String> prefixes = pagePrefixes(pageId);
        double boost = properties.getPageBoost();

        // 원본 Document/score 는 불변(getScore() 읽기전용)이므로, 비교 시점에 유효점수=score+가산점 으로만 정렬.
        List<Document> ranked = fetched.stream()
                .sorted(Comparator.comparingDouble((Document d) -> effectiveScore(d, prefixes, boost)).reversed())
                .limit(topK)
                .collect(Collectors.toList());

        List<String> sources = ranked.stream()
                .map(doc -> (String) doc.getMetadata().get("source"))
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .collect(Collectors.toList());
        return new RetrievedEvidence(ranked, sources);
    }

    /**
     * pageId 에 매핑된 가중 경로 프리픽스(정규화된 '/' 기준). 모르는 pageId 면 빈 리스트.
     * 소스 경로(pathPrefixes) + 짝이 되는 위키 문서 경로(boostPrefixes) 를 모두 가중 대상으로 합친다.
     */
    private List<String> pagePrefixes(String pageId) {
        PageContextRegistry.PageInfo page = pageContextRegistry.find(pageId);
        if (page == null) {
            return List.of();
        }
        Stream<String> path = page.pathPrefixes() == null ? Stream.empty() : page.pathPrefixes().stream();
        Stream<String> boost = page.boostPrefixes() == null ? Stream.empty() : page.boostPrefixes().stream();
        return Stream.concat(path, boost)
                .filter(p -> p != null && !p.isBlank())
                .map(p -> p.replace('\\', '/'))
                .collect(Collectors.toList());
    }

    /** 유사도 score(없으면 0)에, source 경로가 현재 페이지 프리픽스로 시작하면 가산점을 더한 유효점수. */
    private double effectiveScore(Document doc, List<String> prefixes, double boost) {
        Double score = doc.getScore();
        double base = score == null ? 0.0 : score;
        if (prefixes.isEmpty()) {
            return base;
        }
        Object source = doc.getMetadata().get("source");
        if (!(source instanceof String src) || src.isBlank()) {
            return base;
        }
        String normalized = src.replace('\\', '/');
        for (String prefix : prefixes) {
            if (normalized.startsWith(prefix)) {
                return base + boost;
            }
        }
        return base;
    }
}
