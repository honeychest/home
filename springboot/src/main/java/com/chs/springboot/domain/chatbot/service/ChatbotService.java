// [AGENT] 역할: RAG 기반 코드베이스 질의응답 서비스 | 연관파일: ChatbotConfig.java, PgVectorConfig.java, ChatbotController.java, ChatRequest.java, ChatResponse.java
package com.chs.springboot.domain.chatbot.service;

import com.chs.springboot.domain.chatbot.dto.ChatRequest;
import com.chs.springboot.domain.chatbot.dto.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class ChatbotService {

    private static final Logger log = LoggerFactory.getLogger(ChatbotService.class);

    // 맥락으로 실어보낼 최근 대화 최대 개수(토큰 절약·과거 잡음 차단). 클라가 더 보내도 서버에서 자른다.
    private static final int MAX_HISTORY = 12;
    // 후속질문("자세히 설명해줘" 등)은 그 자체로 키워드가 없어 검색이 빗나간다.
    // 직전 사용자 질문 최근 N개를 검색질의에 합쳐 맥락을 보강한다. (LLM에 보내는 질문 문장은 원문 유지)
    private static final int SEARCH_CONTEXT_TURNS = 2;
    // "이어가기 단문" 판단: 이 글자수 미만이거나 아래 접두어로 시작하면 직전 주제를 잇는 호응으로 본다.
    private static final int CONTINUATION_MAX_LEN = 10;
    private static final List<String> CONTINUATION_PREFIXES = List.of(
            "응", "그래", "네", "예", "ㅇㅇ", "어", "계속", "자세히", "더", "그거", "맞아", "ok", "okay");
    // "이 페이지/여기/이 화면" 처럼 현재 화면을 가리키는 지시어. 이런 질문일 때만 pageId 로 검색을 보강한다.
    // (일반 용어 질문 "오픈포지션이 뭐야?" 는 페이지에 매이면 안 되므로 검색을 건드리지 않는다.)
    private static final List<String> PAGE_REFERENCE_HINTS = List.of(
            "이 페이지", "이페이지", "현재 페이지", "이 화면", "이화면", "이 대시보드", "여기", "이 기능", "이거 뭐", "이건 뭐", "이 페이지는");

    private final EvidenceRetriever evidenceRetriever;
    private final GroundedAnswerGenerator answerGenerator;
    private final PageContextRegistry pageContextRegistry;

    public ChatbotService(EvidenceRetriever evidenceRetriever, GroundedAnswerGenerator answerGenerator,
                          PageContextRegistry pageContextRegistry) {
        this.evidenceRetriever = evidenceRetriever;
        this.answerGenerator = answerGenerator;
        this.pageContextRegistry = pageContextRegistry;
    }

    public ChatResponse ask(String question, List<ChatRequest.Turn> history, String pageId) {
        log.info("[챗봇] 질문 수신: {} (pageId={})", question, pageId);
        long startMs = System.currentTimeMillis();

        try {
            PageContextRegistry.PageInfo page = pageContextRegistry.find(pageId);

            String searchQuery = buildSearchQuery(question, history);
            // "이 페이지" 류 질문이면, 그 화면을 가리키는 명사를 검색질의에 덧붙여 올바른 페이지 문서를 찾게 한다.
            if (page != null && referencesCurrentPage(question)) {
                searchQuery = page.searchTerms() + " " + searchQuery;
                log.info("[챗봇] 현재 페이지 지시어 감지 → 검색 페이지 보강: {}", page.label());
            }
            if (!searchQuery.equals(question)) {
                log.info("[챗봇] 검색 맥락 보강 질의: {}", searchQuery);
            }

            RetrievedEvidence evidence = evidenceRetriever.retrieve(searchQuery);
            log.info("[챗봇] 검색된 청크 수: {}", evidence.documentCount());
            log.info("[챗봇] 근거 파일 목록: {}", evidence.sources());

            // "응" 같은 이어가기 단문은 그대로 LLM에 주면 질문으로 인식 못 한다.
            // 이때만 맥락 보강본을 LLM 질문으로 써서 직전 주제를 잇게 한다. (새 주제 질문은 원문 유지)
            String llmQuestion = isContinuation(question) ? searchQuery : question;
            if (!llmQuestion.equals(question)) {
                log.info("[챗봇] 이어가기 단문 감지 → LLM 질문 보강: {}", llmQuestion);
            }

            // 현재 화면 안내문(LLM 이 "이 페이지" 를 해석하도록). 없으면 null → 생성기가 무시.
            String pageContext = page == null ? null : page.promptHint() + " (" + page.label() + ")";

            String answer = answerGenerator.generate(llmQuestion, searchQuery, toMessages(history), pageContext);

            long elapsedMs = System.currentTimeMillis() - startMs;
            log.info("[챗봇] 답변 생성 소요시간: {}ms, 답변 길이: {}자", elapsedMs, answer == null ? 0 : answer.length());

            return new ChatResponse(answer, evidence.sources());

        } catch (Exception e) {
            log.error("[챗봇] 답변 생성 중 오류 발생: {}", e.getMessage(), e);
            return new ChatResponse("오류: " + e.getMessage(), Collections.emptyList());
        }
    }

    /** "이 페이지/여기/이 화면" 처럼 사용자가 보고 있는 현재 화면을 가리키는 지시어가 들어있는지 판단. */
    private boolean referencesCurrentPage(String question) {
        if (question == null) {
            return false;
        }
        String q = question.toLowerCase();
        for (String hint : PAGE_REFERENCE_HINTS) {
            if (q.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    /** 직전 주제를 잇는 짧은 호응("응","자세히" 등)인지 판단. 글자수가 짧거나 정해진 접두어로 시작하면 true. */
    private boolean isContinuation(String question) {
        if (question == null) {
            return false;
        }
        String q = question.trim().toLowerCase();
        if (q.isEmpty()) {
            return false;
        }
        if (q.length() < CONTINUATION_MAX_LEN) {
            return true;
        }
        for (String prefix : CONTINUATION_PREFIXES) {
            if (q.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 근거 검색용 질의 생성. 직전 사용자 질문 최근 SEARCH_CONTEXT_TURNS 개를 현재 질문 앞에 붙여
     * "자세히 설명해줘" 같은 맥락 의존 후속질문도 올바른 문서를 찾게 한다. 이력이 없으면 질문 원문 그대로.
     */
    private String buildSearchQuery(String question, List<ChatRequest.Turn> history) {
        if (history == null || history.isEmpty()) {
            return question;
        }
        List<String> recentUserAsks = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0 && recentUserAsks.size() < SEARCH_CONTEXT_TURNS; i--) {
            ChatRequest.Turn turn = history.get(i);
            if (turn == null || turn.content() == null || turn.content().isBlank()) {
                continue;
            }
            if (!"assistant".equalsIgnoreCase(turn.role())) {
                recentUserAsks.add(turn.content().trim());
            }
        }
        if (recentUserAsks.isEmpty()) {
            return question;
        }
        Collections.reverse(recentUserAsks); // 오래된→최신 순서로
        return String.join(" ", recentUserAsks) + " " + question;
    }

    /** 클라이언트 대화이력을 Spring AI 메시지로 변환(최근 MAX_HISTORY 개만). null/빈 입력은 빈 리스트. */
    private List<Message> toMessages(List<ChatRequest.Turn> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, history.size() - MAX_HISTORY);
        List<ChatRequest.Turn> recent = history.subList(from, history.size());
        List<Message> messages = new ArrayList<>(recent.size());
        for (ChatRequest.Turn turn : recent) {
            if (turn == null || turn.content() == null || turn.content().isBlank()) {
                continue;
            }
            if ("assistant".equalsIgnoreCase(turn.role())) {
                messages.add(new AssistantMessage(turn.content()));
            } else {
                messages.add(new UserMessage(turn.content()));
            }
        }
        return messages;
    }
}
