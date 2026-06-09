// [AGENT] 역할: 챗봇 질문 요청 DTO (Jackson 역직렬화용) | 연관파일: ChatbotController.java, ChatbotService.java
package com.chs.springboot.domain.chatbot.dto;

public class ChatRequest {

    private String question;

    public ChatRequest() {
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }
}
