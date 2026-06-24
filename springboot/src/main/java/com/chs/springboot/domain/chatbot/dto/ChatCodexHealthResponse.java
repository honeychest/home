// [AGENT] 역할: 챗봇 Codex external runner 헬스체크 응답 DTO | 연관파일: ChatbotController.java
package com.chs.springboot.domain.chatbot.dto;

public class ChatCodexHealthResponse {

    private final boolean healthy;
    private final String status;
    private final String answer;
    private final String errorMessage;

    public ChatCodexHealthResponse(boolean healthy, String status, String answer, String errorMessage) {
        this.healthy = healthy;
        this.status = status;
        this.answer = answer;
        this.errorMessage = errorMessage;
    }

    public boolean isHealthy() {
        return healthy;
    }

    public String getStatus() {
        return status;
    }

    public String getAnswer() {
        return answer;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
