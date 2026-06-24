// [AGENT] 역할: external Codex runner 실행 결과 값 | 연관파일: CodexRunnerClient.java, ChatbotService.java
package com.chs.springboot.domain.chatbot.service;

public record CodexRunResult(
        CodexRunStatus status,
        String answer,
        String stdout,
        String stderr,
        String errorMessage
) {
    public static CodexRunResult success(String answer, String stdout, String stderr) {
        return new CodexRunResult(CodexRunStatus.SUCCESS, answer, stdout, stderr, null);
    }

    public static CodexRunResult failure(CodexRunStatus status, String errorMessage) {
        return new CodexRunResult(status, null, null, null, errorMessage);
    }

    public boolean isSuccess() {
        return status == CodexRunStatus.SUCCESS && answer != null && !answer.isBlank();
    }
}
