// [AGENT] 역할: external Codex runner 실행 결과 상태 | 연관파일: CodexRunnerClient.java, ChatbotService.java
package com.chs.springboot.domain.chatbot.service;

public enum CodexRunStatus {
    SUCCESS("success"),
    CODEX_NOT_AVAILABLE("codex_not_available"),
    TIMEOUT("timeout"),
    AUTH_REQUIRED("auth_required"),
    UPDATE_REQUIRED("update_required"),
    EXECUTION_FAILED("execution_failed");

    private final String wireValue;

    CodexRunStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static CodexRunStatus fromWireValue(String value) {
        if (value == null || value.isBlank()) {
            return EXECUTION_FAILED;
        }
        String normalized = value.trim().toLowerCase();
        for (CodexRunStatus status : values()) {
            if (status.wireValue.equals(normalized)) {
                return status;
            }
        }
        return EXECUTION_FAILED;
    }
}
