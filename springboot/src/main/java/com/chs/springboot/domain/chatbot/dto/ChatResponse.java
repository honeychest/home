// [AGENT] 역할: 챗봇 답변 응답 DTO (answer + 근거 파일 목록) | 연관파일: ChatbotController.java, ChatbotService.java
package com.chs.springboot.domain.chatbot.dto;

import java.util.List;

public class ChatResponse {

    private final String answer;
    private final List<String> sources;
    private final String requestedModel;
    private final String effectiveModel;
    private final int codexLimitPerChat;
    private final int remainingCodexUses;
    private final String fallbackReason;
    private final String notice;

    public ChatResponse(String answer, List<String> sources) {
        this(answer, sources, null, null, 0, 0, null, null);
    }

    public ChatResponse(String answer, List<String> sources, String requestedModel, String effectiveModel,
                        int codexLimitPerChat, int remainingCodexUses, String fallbackReason, String notice) {
        this.answer = answer;
        this.sources = sources;
        this.requestedModel = requestedModel;
        this.effectiveModel = effectiveModel;
        this.codexLimitPerChat = codexLimitPerChat;
        this.remainingCodexUses = remainingCodexUses;
        this.fallbackReason = fallbackReason;
        this.notice = notice;
    }

    public String getAnswer() {
        return answer;
    }

    public List<String> getSources() {
        return sources;
    }

    public String getRequestedModel() {
        return requestedModel;
    }

    public String getEffectiveModel() {
        return effectiveModel;
    }

    public int getCodexLimitPerChat() {
        return codexLimitPerChat;
    }

    public int getRemainingCodexUses() {
        return remainingCodexUses;
    }

    public String getFallbackReason() {
        return fallbackReason;
    }

    public String getNotice() {
        return notice;
    }
}
