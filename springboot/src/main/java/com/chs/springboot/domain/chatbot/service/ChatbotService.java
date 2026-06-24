// [AGENT] 역할: RAG 기반 코드베이스 질의응답 서비스 | 연관파일: ChatbotConfig.java, PgVectorConfig.java, ChatbotController.java, ChatRequest.java, ChatResponse.java
package com.chs.springboot.domain.chatbot.service;

import com.chs.springboot.domain.chatbot.config.ChatbotProperties;
import com.chs.springboot.domain.chatbot.dto.ChatCodexHealthResponse;
import com.chs.springboot.domain.chatbot.dto.ChatModelPolicyResponse;
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
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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

    private final EvidenceRetriever evidenceRetriever;
    private final GroundedAnswerGenerator answerGenerator;
    private final CodexAnswerGenerator codexAnswerGenerator;
    private final PageContextRegistry pageContextRegistry;
    private final ChatbotLogService chatbotLogService;
    private final ChatbotProperties properties;
    private final ConcurrentHashMap<String, AtomicInteger> codexUsageBySession = new ConcurrentHashMap<>();

    public ChatbotService(EvidenceRetriever evidenceRetriever, GroundedAnswerGenerator answerGenerator,
                          CodexAnswerGenerator codexAnswerGenerator, PageContextRegistry pageContextRegistry,
                          ChatbotLogService chatbotLogService, ChatbotProperties properties) {
        this.evidenceRetriever = evidenceRetriever;
        this.answerGenerator = answerGenerator;
        this.codexAnswerGenerator = codexAnswerGenerator;
        this.pageContextRegistry = pageContextRegistry;
        this.chatbotLogService = chatbotLogService;
        this.properties = properties;
    }

    public ChatModelPolicyResponse modelPolicy(String sessionId) {
        return new ChatModelPolicyResponse(codexLimit(), remainingCodexUses(sessionId), "CODEX", "LOCAL");
    }

    public ChatCodexHealthResponse codexHealth() {
        CodexRunResult result = codexAnswerGenerator.healthCheck();
        return new ChatCodexHealthResponse(
                result.isSuccess() && "OK".equalsIgnoreCase(result.answer().trim()),
                result.status().wireValue(),
                result.answer(),
                result.errorMessage()
        );
    }

    public ChatResponse ask(String question, List<ChatRequest.Turn> history, String pageId, String sessionId,
                            String requestedModel) {
        String normalizedModel = normalizeModel(requestedModel);
        log.info("[챗봇] 질문 수신: {} (pageId={}, requestedModel={})", question, pageId, normalizedModel);
        long startMs = System.currentTimeMillis();

        try {
            PageContextRegistry.PageInfo page = pageContextRegistry.find(pageId);

            String searchQuery = buildSearchQuery(question, history);
            if (!searchQuery.equals(question)) {
                log.info("[챗봇] 검색 맥락 보강 질의: {}", searchQuery);
            }

            // 현재 페이지 신호는 텍스트 키워드 추측이 아니라 pageId 메타데이터 가중(EvidenceRetriever)으로 반영한다.
            RetrievedEvidence evidence = evidenceRetriever.retrieve(searchQuery, pageId);
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

            AnswerResult answerResult = generateAnswer(
                    normalizedModel, sessionId, llmQuestion, searchQuery, history, pageContext, evidence);

            long elapsedMs = System.currentTimeMillis() - startMs;
            log.info("[챗봇] 답변 생성 소요시간: {}ms, model={}→{}, 답변 길이: {}자",
                    elapsedMs, normalizedModel, answerResult.effectiveModel(),
                    answerResult.answer() == null ? 0 : answerResult.answer().length());
            recordSuccess(sessionId, pageId, question, answerResult.answer(), searchQuery, llmQuestion, pageContext,
                    evidence, elapsedMs);

            return new ChatResponse(
                    answerResult.answer(),
                    evidence.sources(),
                    normalizedModel,
                    answerResult.effectiveModel(),
                    codexLimit(),
                    remainingCodexUses(sessionId),
                    answerResult.fallbackReason(),
                    answerResult.notice()
            );

        } catch (Exception e) {
            long elapsedMs = System.currentTimeMillis() - startMs;
            log.error("[챗봇] 답변 생성 중 오류 발생: {}", e.getMessage(), e);
            recordError(sessionId, pageId, question, elapsedMs, e.getMessage());
            return new ChatResponse("오류: " + e.getMessage(), Collections.emptyList(),
                    normalizedModel, "LOCAL", codexLimit(), remainingCodexUses(sessionId),
                    "LOCAL_FAILED", "LOCAL 모델 응답 중 오류가 발생했습니다.");
        }
    }

    private AnswerResult generateAnswer(String requestedModel, String sessionId, String llmQuestion, String searchQuery,
                                        List<ChatRequest.Turn> history, String pageContext,
                                        RetrievedEvidence evidence) {
        if (!"CODEX".equals(requestedModel)) {
            String answer = answerGenerator.generate(llmQuestion, searchQuery, toMessages(history), pageContext);
            return new AnswerResult(answer, "LOCAL", null, null);
        }

        int remaining = remainingCodexUses(sessionId);
        if (remaining <= 0) {
            String notice = "이 채팅방의 Codex 사용 횟수 " + codexLimit()
                    + "회를 모두 사용해 LOCAL 모델로 전환했습니다. LOCAL 모델은 30초 이상 걸릴 수 있습니다.";
            String answer = answerGenerator.generate(llmQuestion, searchQuery, toMessages(history), pageContext);
            return new AnswerResult(answer, "LOCAL", "codex_limit_exceeded", notice);
        }

        try {
            CodexRunResult codexResult = codexAnswerGenerator.generate(
                    llmQuestion, searchQuery, history, pageContext, evidence);
            if (!codexResult.isSuccess()) {
                String notice = codexFailureNotice(codexResult.status());
                String answer = answerGenerator.generate(llmQuestion, searchQuery, toMessages(history), pageContext);
                return new AnswerResult(answer, "LOCAL", codexResult.status().wireValue(), notice);
            }
            incrementCodexUsage(sessionId);
            return new AnswerResult(codexResult.answer(), "CODEX", null, null);
        } catch (RuntimeException codexError) {
            log.warn("[챗봇] Codex external runner 오류 → LOCAL 폴백: {}", codexError.getMessage(), codexError);
            String notice = codexFailureNotice(CodexRunStatus.EXECUTION_FAILED);
            String answer = answerGenerator.generate(llmQuestion, searchQuery, toMessages(history), pageContext);
            return new AnswerResult(answer, "LOCAL", CodexRunStatus.EXECUTION_FAILED.wireValue(), notice);
        }
    }

    private String codexFailureNotice(CodexRunStatus status) {
        String reason = switch (status) {
            case CODEX_NOT_AVAILABLE -> "Codex external runner를 사용할 수 없어";
            case TIMEOUT -> "Codex external runner 응답 시간이 초과되어";
            case AUTH_REQUIRED -> "Codex 인증이 필요해";
            case UPDATE_REQUIRED -> "Codex runner 업데이트가 필요해";
            case EXECUTION_FAILED, SUCCESS -> "Codex 실행에 실패해";
        };
        return reason + " LOCAL 모델로 다시 시도했습니다. LOCAL 모델은 30초 이상 걸릴 수 있습니다.";
    }

    private int remainingCodexUses(String sessionId) {
        return Math.max(0, codexLimit() - usedCodexCount(sessionId));
    }

    private void incrementCodexUsage(String sessionId) {
        codexUsageBySession.computeIfAbsent(usageKey(sessionId), ignored -> new AtomicInteger()).incrementAndGet();
    }

    private int usedCodexCount(String sessionId) {
        AtomicInteger count = codexUsageBySession.get(usageKey(sessionId));
        return count == null ? 0 : count.get();
    }

    private int codexLimit() {
        return Math.max(0, properties.getModel().getCodexLimitPerChat());
    }

    private String usageKey(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? "anonymous" : sessionId.trim();
    }

    private String normalizeModel(String requestedModel) {
        if (requestedModel == null || requestedModel.isBlank()) {
            return "CODEX";
        }
        String normalized = requestedModel.trim().toUpperCase(Locale.ROOT);
        return "LOCAL".equals(normalized) ? "LOCAL" : "CODEX";
    }

    private record AnswerResult(String answer, String effectiveModel, String fallbackReason, String notice) {
    }

    private void recordSuccess(String sessionId, String pageId, String question, String answer, String searchQuery,
                               String llmQuestion, String pageContext, RetrievedEvidence evidence, long elapsedMs) {
        try {
            chatbotLogService.recordSuccess(sessionId, pageId, question, answer, searchQuery, llmQuestion, pageContext,
                    evidence, elapsedMs);
        } catch (Exception logError) {
            log.warn("[챗봇] 로그 저장 실패(답변은 유지): {}", logError.getMessage(), logError);
        }
    }

    private void recordError(String sessionId, String pageId, String question, long elapsedMs, String errorMessage) {
        try {
            chatbotLogService.recordError(sessionId, pageId, question, elapsedMs, errorMessage);
        } catch (Exception logError) {
            log.warn("[챗봇] 오류 로그 저장 실패: {}", logError.getMessage(), logError);
        }
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
