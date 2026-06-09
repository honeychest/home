// [AGENT] 역할: 챗봇 답변 응답 DTO (answer + 근거 파일 목록) | 연관파일: ChatbotController.java, ChatbotService.java
package com.chs.springboot.domain.chatbot.dto;

import java.util.List;

public class ChatResponse {

    private final String answer;
    private final List<String> sources;

    public ChatResponse(String answer, List<String> sources) {
        this.answer = answer;
        this.sources = sources;
    }

    public String getAnswer() {
        return answer;
    }

    public List<String> getSources() {
        return sources;
    }
}
