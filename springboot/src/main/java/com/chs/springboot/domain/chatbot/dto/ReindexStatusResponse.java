// [AGENT] 역할: 재색인 상태조회 API 응답 DTO | 연관파일: ChatbotAdminController.java, ReindexJob.java
package com.chs.springboot.domain.chatbot.dto;

public record ReindexStatusResponse(
        String jobId,
        String status,
        int documentCount,
        int processedChunks,
        int totalChunks,
        String error
) {
    public static ReindexStatusResponse notFound(String jobId) {
        return new ReindexStatusResponse(jobId, "NOT_FOUND", 0, 0, 0, "job not found");
    }
}
