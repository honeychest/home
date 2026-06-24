// [AGENT] 역할: Codex 실행 위임 인터페이스 | 연관파일: ExternalCodexRunnerClient.java, CodexAnswerGenerator.java
package com.chs.springboot.domain.chatbot.service;

public interface CodexRunnerClient {

    CodexRunResult run(CodexRunRequest request);
}
