// [AGENT] 역할: 챗봇 로그 분석 요약 응답 DTO | 연관파일: ChatbotAdminController.java, ChatbotLogService.java
package com.chs.springboot.domain.chatbot.dto;

public record ChatbotLogSummaryResponse(
        long totalLogs,
        long suspectedLogs,
        double averageLatencySeconds,
        long slowLogs
) {
}
