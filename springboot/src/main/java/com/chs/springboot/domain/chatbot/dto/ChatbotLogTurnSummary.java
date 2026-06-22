// [AGENT] 역할: 챗봇 로그 목록 행 DTO | 연관파일: ChatbotLogService.java
package com.chs.springboot.domain.chatbot.dto;

import java.time.LocalDateTime;

public record ChatbotLogTurnSummary(
        Long id,
        String pageId,
        LocalDateTime createdAt,
        Integer latencyMs,
        String status,
        String issueType,
        String question,
        String answerPreview
) {
}
