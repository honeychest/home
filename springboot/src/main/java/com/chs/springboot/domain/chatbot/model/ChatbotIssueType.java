// [AGENT] 역할: 챗봇 로그 분석에서 사용하는 문제 유형 enum | 연관파일: ChatbotTurn.java, ChatbotAnalysis.java
package com.chs.springboot.domain.chatbot.model;

public enum ChatbotIssueType {
    NONE,
    RETRIEVAL_MISS,
    ANSWER_QUALITY,
    CONTEXT_MISS,
    PAGE_CONTEXT_MISS,
    LATENCY,
    ERROR
}
