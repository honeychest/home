// [AGENT] 역할: 색인 작업 상태 보관 POJO | 연관파일: CodebaseIndexingService.java, ChatbotAdminController.java
package com.chs.springboot.domain.chatbot.dto;

public class ReindexJob {

    private final String jobId;
    private volatile ReindexJobStatus status;
    private volatile int documentCount;
    private volatile String error;
    private volatile long startedAtMs;
    private volatile long finishedAtMs;
    private volatile int totalChunks;
    private volatile int processedChunks;

    public ReindexJob(String jobId) {
        this.jobId = jobId;
        this.status = ReindexJobStatus.RUNNING;
        this.startedAtMs = System.currentTimeMillis();
    }

    public void markCompleted(int count) {
        this.documentCount = count;
        this.status = ReindexJobStatus.COMPLETED;
        this.finishedAtMs = System.currentTimeMillis();
    }

    public void markFailed(String errorMessage) {
        this.error = errorMessage;
        this.status = ReindexJobStatus.FAILED;
        this.finishedAtMs = System.currentTimeMillis();
    }

    public String getJobId() {
        return jobId;
    }

    public String getStatus() {
        return status.name();
    }

    public int getDocumentCount() {
        return documentCount;
    }

    public String getError() {
        return error;
    }

    public long getStartedAtMs() {
        return startedAtMs;
    }

    public long getFinishedAtMs() {
        return finishedAtMs;
    }

    public void setTotalChunks(int totalChunks) {
        this.totalChunks = totalChunks;
    }

    public void setProcessedChunks(int processedChunks) {
        this.processedChunks = processedChunks;
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public int getProcessedChunks() {
        return processedChunks;
    }

    public ReindexStatusResponse toStatusResponse() {
        return new ReindexStatusResponse(
                jobId,
                status.name(),
                documentCount,
                processedChunks,
                totalChunks,
                error == null ? "" : error
        );
    }
}
