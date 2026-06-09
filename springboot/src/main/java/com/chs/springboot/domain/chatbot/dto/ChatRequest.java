// [AGENT] 역할: 챗봇 질문 요청 DTO (Jackson 역직렬화용) | 연관파일: ChatbotController.java, ChatbotService.java
package com.chs.springboot.domain.chatbot.dto;

import java.util.List;

public class ChatRequest {

    private String question;
    // 클라이언트(채팅창)가 보유한 이전 대화. 서버는 저장하지 않고 매 요청의 맥락으로만 사용한다. (없어도 동작)
    private List<Turn> history;

    public ChatRequest() {
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public List<Turn> getHistory() {
        return history;
    }

    public void setHistory(List<Turn> history) {
        this.history = history;
    }

    /** 한 발화. role 은 "user" 또는 "assistant". */
    public record Turn(String role, String content) {
    }
}
