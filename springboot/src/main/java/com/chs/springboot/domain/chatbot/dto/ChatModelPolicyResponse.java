// [AGENT] 역할: 챗봇 모델 선택/횟수 제한 상태 응답 DTO | 연관파일: ChatbotController.java, ChatbotService.java
package com.chs.springboot.domain.chatbot.dto;

public class ChatModelPolicyResponse {

    private final int codexLimitPerChat;
    private final int remainingCodexUses;
    private final String defaultModel;
    private final String fallbackModel;

    public ChatModelPolicyResponse(int codexLimitPerChat, int remainingCodexUses,
                                   String defaultModel, String fallbackModel) {
        this.codexLimitPerChat = codexLimitPerChat;
        this.remainingCodexUses = remainingCodexUses;
        this.defaultModel = defaultModel;
        this.fallbackModel = fallbackModel;
    }

    public int getCodexLimitPerChat() {
        return codexLimitPerChat;
    }

    public int getRemainingCodexUses() {
        return remainingCodexUses;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public String getFallbackModel() {
        return fallbackModel;
    }
}
