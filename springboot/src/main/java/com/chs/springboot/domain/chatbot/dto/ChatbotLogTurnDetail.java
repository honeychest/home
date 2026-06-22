// [AGENT] 역할: 챗봇 로그 상세 DTO | 연관파일: ChatbotLogService.java
package com.chs.springboot.domain.chatbot.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ChatbotLogTurnDetail(
        Long id,
        String sessionId,
        String pageId,
        LocalDateTime createdAt,
        LocalDateTime completedAt,
        Integer latencyMs,
        String status,
        String issueType,
        String question,
        String answer,
        String searchQuery,
        String llmQuestion,
        String pageContext,
        String errorMessage,
        List<Evidence> evidences,
        List<Analysis> analyses
) {
    public record Evidence(
            Integer rankNo,
            String source,
            String symbol,
            String lineRange,
            String contentPreview
    ) {
    }

    public record Analysis(
            String issueType,
            String summary,
            String suggestion,
            String createdBy,
            LocalDateTime createdAt
    ) {
    }
}
