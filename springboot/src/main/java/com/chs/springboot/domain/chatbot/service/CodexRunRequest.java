// [AGENT] 역할: external Codex runner 실행 요청 값 | 연관파일: CodexRunnerClient.java
package com.chs.springboot.domain.chatbot.service;

public record CodexRunRequest(String prompt, int timeoutSeconds) {
}
