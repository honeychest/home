// [AGENT] 역할: 챗봇 질문 요청 DTO (Jackson 역직렬화용) | 연관파일: ChatbotController.java, ChatbotService.java
package com.chs.springboot.domain.chatbot.dto;

import java.util.List;

public class ChatRequest {

    private String question;
    // 클라이언트(채팅창)가 보유한 이전 대화. 서버는 저장하지 않고 매 요청의 맥락으로만 사용한다. (없어도 동작)
    private List<Turn> history;
    // 사용자가 질문할 때 보고 있던 화면 식별자(예: "signal", "analysis"). 프론트가 라우트에서 파생해 보낸다.
    // "이 페이지 뭐야?" 류 질문을 올바른 페이지로 해석/검색하기 위한 힌트. 없어도 동작(하위호환).
    private String pageId;
    // 브라우저 채팅창 단위 대화 식별자. 서버 로그에서 여러 질문/답변을 하나의 대화 흐름으로 묶는 데 사용한다.
    private String sessionId;

    public ChatRequest() {
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getPageId() {
        return pageId;
    }

    public void setPageId(String pageId) {
        this.pageId = pageId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
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
