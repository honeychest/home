// [AGENT] 역할: 챗봇 모델 선택/횟수 제한 상태 응답 DTO | 연관파일: ChatbotController.java, ChatbotService.java
package com.chs.springboot.domain.chatbot.dto;

public class ChatModelPolicyResponse {

    private final int codexLimitPerChat;
    private final int remainingCodexUses;
    private final boolean codexEnabled;
    private final String defaultModel;
    private final String fallbackModel;

    public ChatModelPolicyResponse(int codexLimitPerChat, int remainingCodexUses, boolean codexEnabled,
                                   String defaultModel, String fallbackModel) {
        this.codexLimitPerChat = codexLimitPerChat;
        this.remainingCodexUses = remainingCodexUses;
        this.codexEnabled = codexEnabled;
        this.defaultModel = defaultModel;
        this.fallbackModel = fallbackModel;
    }

    public int getCodexLimitPerChat() {
        return codexLimitPerChat;
    }

    public int getRemainingCodexUses() {
        return remainingCodexUses;
    }

    public boolean isCodexEnabled() {
        return codexEnabled;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public String getFallbackModel() {
        return fallbackModel;
    }
}
