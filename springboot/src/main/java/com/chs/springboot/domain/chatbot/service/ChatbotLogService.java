// [AGENT] 역할: 챗봇 질문/답변/근거 로그 저장 및 관리자 조회 | 연관파일: ChatbotService.java, ChatbotAdminController.java
package com.chs.springboot.domain.chatbot.service;

import com.chs.springboot.domain.chatbot.dto.ChatbotLogSummaryResponse;
import com.chs.springboot.domain.chatbot.dto.ChatbotLogTurnDetail;
import com.chs.springboot.domain.chatbot.dto.ChatbotLogTurnSummary;
import com.chs.springboot.domain.chatbot.model.ChatbotAnalysis;
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
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ChatbotLogService {

    private static final int SLOW_THRESHOLD_MS = 20_000;
    private static final int QUESTION_MAX_LENGTH = 2000;
    private static final int ANSWER_MAX_LENGTH = 12000;
    private static final int SEARCH_QUERY_MAX_LENGTH = 3000;
    private static final int LLM_QUESTION_MAX_LENGTH = 3000;
    private static final int PAGE_CONTEXT_MAX_LENGTH = 1000;
    private static final int ERROR_MESSAGE_MAX_LENGTH = 4000;
    private static final int PREVIEW_MAX_LENGTH = 1000;
    private static final int METADATA_JSON_MAX_LENGTH = 4000;
    private static final int SOURCE_MAX_LENGTH = 500;
    private static final int SYMBOL_MAX_LENGTH = 255;
    private static final int LINE_RANGE_MAX_LENGTH = 40;

    private final ChatbotConversationRepository conversationRepository;
    private final ChatbotTurnRepository turnRepository;
    private final ChatbotRetrievedEvidenceRepository evidenceRepository;
    private final ChatbotAnalysisRepository analysisRepository;
    private final ObjectMapper objectMapper;
    private final String sourceEnv;

    public ChatbotLogService(ChatbotConversationRepository conversationRepository,
                             ChatbotTurnRepository turnRepository,
                             ChatbotRetrievedEvidenceRepository evidenceRepository,
                             ChatbotAnalysisRepository analysisRepository,
                             ObjectMapper objectMapper,
                             @Value("${monitor.alert-history.source-env:${spring.profiles.active:unknown}}")
                             String sourceEnv) {
        this.conversationRepository = conversationRepository;
        this.turnRepository = turnRepository;
        this.evidenceRepository = evidenceRepository;
        this.analysisRepository = analysisRepository;
        this.objectMapper = objectMapper;
        this.sourceEnv = normalize(sourceEnv, "unknown");
    }

    @Transactional
    public void recordSuccess(String sessionId, String pageId, String question, String answer,
                              String searchQuery, String llmQuestion, String pageContext,
                              RetrievedEvidence evidence, long latencyMs) {
        ChatbotConversation conversation = findOrCreateConversation(sessionId, pageId);
        ChatbotTurn turn = baseTurn(conversation, pageId, question);
        turn.setAnswer(limit(answer, ANSWER_MAX_LENGTH));
        turn.setSearchQuery(limit(searchQuery, SEARCH_QUERY_MAX_LENGTH));
        turn.setLlmQuestion(limit(llmQuestion, LLM_QUESTION_MAX_LENGTH));
        turn.setPageContext(limit(pageContext, PAGE_CONTEXT_MAX_LENGTH));
        turn.setStatus(ChatbotTurnStatus.SUCCESS);
        turn.setIssueType(latencyMs >= SLOW_THRESHOLD_MS ? ChatbotIssueType.LATENCY : ChatbotIssueType.NONE);
        turn.setLatencyMs(safeInt(latencyMs));
        turn.setEvidenceCount(evidence == null ? 0 : evidence.documentCount());
        turn.setCompletedAt(LocalDateTime.now());
        ChatbotTurn saved = turnRepository.save(turn);
        saveEvidence(saved, evidence);
    }

    @Transactional
    public void recordError(String sessionId, String pageId, String question, long latencyMs, String errorMessage) {
        ChatbotConversation conversation = findOrCreateConversation(sessionId, pageId);
        ChatbotTurn turn = baseTurn(conversation, pageId, question);
        turn.setStatus(ChatbotTurnStatus.ERROR);
        turn.setIssueType(ChatbotIssueType.ERROR);
        turn.setLatencyMs(safeInt(latencyMs));
        turn.setEvidenceCount(0);
        turn.setErrorMessage(limit(errorMessage, ERROR_MESSAGE_MAX_LENGTH));
        turn.setCompletedAt(LocalDateTime.now());
        turnRepository.save(turn);
    }

    @Transactional(readOnly = true)
    public ChatbotLogSummaryResponse summarize(LocalDateTime from, LocalDateTime to, String pageId,
                                               ChatbotIssueType issueType, ChatbotTurnStatus status,
                                               Integer minLatencyMs, String keyword) {
        String normalizedPageId = blankToNull(pageId);
        String normalizedKeyword = blankToNull(keyword);
        long total = turnRepository.countByFilters(
                from, to, sourceEnv, normalizedPageId, issueType, status, minLatencyMs, normalizedKeyword);
        long suspected = turnRepository.countSuspectedByFilters(
                from, to, sourceEnv, normalizedPageId, issueType, status, minLatencyMs, normalizedKeyword);
        Double avgMs = turnRepository.averageLatencyByFilters(
                from, to, sourceEnv, normalizedPageId, issueType, status, minLatencyMs, normalizedKeyword);
        long slow = turnRepository.countSlowByFilters(
                from, to, sourceEnv, normalizedPageId, issueType, status, minLatencyMs, normalizedKeyword,
                SLOW_THRESHOLD_MS);
        return new ChatbotLogSummaryResponse(total, suspected, roundSeconds(avgMs), slow);
    }

    @Transactional(readOnly = true)
    public Page<ChatbotLogTurnSummary> findTurns(LocalDateTime from, LocalDateTime to, String pageId,
                                                 ChatbotIssueType issueType, ChatbotTurnStatus status,
                                                 Integer minLatencyMs, String keyword, Pageable pageable) {
        return turnRepository.findByFilters(
                from,
                to,
                sourceEnv,
                blankToNull(pageId),
                issueType,
                status,
                minLatencyMs,
                blankToNull(keyword),
                pageable
        ).map(this::toSummary);
    }

    @Transactional(readOnly = true)
    public ChatbotLogTurnDetail getDetail(Long turnId) {
        ChatbotTurn turn = turnRepository.findById(turnId)
                .orElseThrow(() -> new IllegalArgumentException("chatbot turn not found: " + turnId));
        List<ChatbotLogTurnDetail.Evidence> evidences = evidenceRepository.findByTurnIdOrderByRankNoAsc(turnId)
                .stream()
                .map(this::toEvidence)
                .toList();
        List<ChatbotLogTurnDetail.Analysis> analyses = analysisRepository.findByTurnIdOrderByCreatedAtDesc(turnId)
                .stream()
                .map(this::toAnalysis)
                .toList();
        return new ChatbotLogTurnDetail(
                turn.getId(),
                turn.getConversation().getSessionId(),
                turn.getPageId(),
                turn.getCreatedAt(),
                turn.getCompletedAt(),
                turn.getLatencyMs(),
                turn.getStatus().name(),
                turn.getIssueType().name(),
                turn.getQuestion(),
                turn.getAnswer(),
                turn.getSearchQuery(),
                turn.getLlmQuestion(),
                turn.getPageContext(),
                turn.getErrorMessage(),
                evidences,
                analyses
        );
    }

    private ChatbotConversation findOrCreateConversation(String sessionId, String pageId) {
        String normalizedSessionId = blankToNull(sessionId);
        ChatbotConversation conversation = normalizedSessionId == null
                ? null
                : conversationRepository
                        .findFirstBySessionIdAndSourceEnvOrderByLastMessageAtDesc(normalizedSessionId, sourceEnv)
                        .orElse(null);
        if (conversation == null) {
            conversation = new ChatbotConversation();
            conversation.setSessionId(normalizedSessionId);
            conversation.setPageId(blankToNull(pageId));
            conversation.setSourceEnv(sourceEnv);
            conversation.setMessageCount(0);
            conversation.setStartedAt(LocalDateTime.now());
        }
        conversation.setPageId(blankToNull(pageId));
        conversation.setMessageCount(conversation.getMessageCount() == null ? 1 : conversation.getMessageCount() + 1);
        conversation.setLastMessageAt(LocalDateTime.now());
        return conversationRepository.save(conversation);
    }

    private ChatbotTurn baseTurn(ChatbotConversation conversation, String pageId, String question) {
        ChatbotTurn turn = new ChatbotTurn();
        turn.setConversation(conversation);
        turn.setRequestId(UUID.randomUUID().toString());
        turn.setTurnIndex(conversation.getMessageCount());
        turn.setPageId(blankToNull(pageId));
        turn.setQuestion(limit(question == null ? "" : question, QUESTION_MAX_LENGTH));
        turn.setCreatedAt(LocalDateTime.now());
        return turn;
    }

    private void saveEvidence(ChatbotTurn turn, RetrievedEvidence evidence) {
        if (evidence == null || evidence.documents() == null || evidence.documents().isEmpty()) {
            return;
        }
        int rank = 1;
        for (Document document : evidence.documents()) {
            Map<String, Object> metadata = document.getMetadata();
            ChatbotRetrievedEvidence row = new ChatbotRetrievedEvidence();
            row.setTurn(turn);
            row.setRankNo(rank++);
            row.setSource(limit(stringMeta(metadata, "source", "(unknown)"), SOURCE_MAX_LENGTH));
            row.setSymbol(limit(stringMeta(metadata, "symbol", null), SYMBOL_MAX_LENGTH));
            row.setLineRange(limit(stringMeta(metadata, "lines", null), LINE_RANGE_MAX_LENGTH));
            row.setScore(documentScore(document));
            row.setContentPreview(preview(document.getText()));
            row.setMetadataJson(toJson(metadata));
            evidenceRepository.save(row);
        }
    }

    private ChatbotLogTurnSummary toSummary(ChatbotTurn turn) {
        return new ChatbotLogTurnSummary(
                turn.getId(),
                turn.getPageId(),
                turn.getCreatedAt(),
                turn.getLatencyMs(),
                turn.getStatus().name(),
                turn.getIssueType().name(),
                turn.getQuestion(),
                preview(turn.getAnswer())
        );
    }

    private ChatbotLogTurnDetail.Evidence toEvidence(ChatbotRetrievedEvidence evidence) {
        return new ChatbotLogTurnDetail.Evidence(
                evidence.getRankNo(),
                evidence.getSource(),
                evidence.getSymbol(),
                evidence.getLineRange(),
                evidence.getContentPreview()
        );
    }

    private ChatbotLogTurnDetail.Analysis toAnalysis(ChatbotAnalysis analysis) {
        return new ChatbotLogTurnDetail.Analysis(
                analysis.getIssueType().name(),
                analysis.getSummary(),
                analysis.getSuggestion(),
                analysis.getCreatedBy().name(),
                analysis.getCreatedAt()
        );
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return limit(objectMapper.writeValueAsString(metadata), METADATA_JSON_MAX_LENGTH);
        } catch (Exception e) {
            return "{}";
        }
    }

    private BigDecimal documentScore(Document document) {
        Double score = document.getScore();
        if (score != null) {
            return BigDecimal.valueOf(score);
        }
        return scoreMeta(document.getMetadata());
    }

    private BigDecimal scoreMeta(Map<String, Object> metadata) {
        Object value = metadata.get("score");
        if (value == null) {
            value = metadata.get("distance");
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return null;
    }

    private String stringMeta(Map<String, Object> metadata, String key, String fallback) {
        Object value = metadata.get(key);
        if (value == null) {
            return fallback;
        }
        String text = value.toString();
        return text.isBlank() ? fallback : text;
    }

    private String preview(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text.trim();
        if (normalized.length() <= PREVIEW_MAX_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, PREVIEW_MAX_LENGTH) + "...";
    }

    private String limit(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        if (maxLength <= 3) {
            return text.substring(0, Math.max(0, maxLength));
        }
        return text.substring(0, maxLength - 3) + "...";
    }

    private int safeInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private double roundSeconds(Double milliseconds) {
        if (milliseconds == null) {
            return 0;
        }
        return Math.round((milliseconds / 1000.0) * 10.0) / 10.0;
    }

    private String normalize(String value, String fallback) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return fallback;
        }
        int comma = normalized.indexOf(',');
        return comma >= 0 ? normalized.substring(0, comma).trim() : normalized;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
