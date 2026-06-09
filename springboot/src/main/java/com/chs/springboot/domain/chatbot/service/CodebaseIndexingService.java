// [AGENT] 역할: 색인 작업 오케스트레이션(동시실행 잠금 + 작업상태 보관) | 연관파일: AsyncReindexRunner.java, ReindexJob.java, ChatbotAdminController.java
package com.chs.springboot.domain.chatbot.service;

import com.chs.springboot.domain.chatbot.dto.ReindexJob;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class CodebaseIndexingService {

    private final AsyncReindexRunner runner;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, ReindexJob> jobs = new ConcurrentHashMap<>();

    public CodebaseIndexingService(AsyncReindexRunner runner) {
        this.runner = runner;
    }

    public ReindexJob startReindex() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("reindex already running");
        }
        ReindexJob job = new ReindexJob(UUID.randomUUID().toString());
        jobs.put(job.getJobId(), job);
        // 별도 빈(프록시 경유)으로 비동기 실행. 락 해제는 종료 콜백으로 위임.
        runner.run(job, () -> running.set(false));
        return job;
    }

    public ReindexJob startDocsReindex() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("reindex already running");
        }
        ReindexJob job = new ReindexJob(UUID.randomUUID().toString());
        jobs.put(job.getJobId(), job);
        // 전체 재색인과 동일한 락을 공유해 동시 실행을 막는다. doc 레이어만 증분 색인.
        runner.runDocs(job, () -> running.set(false));
        return job;
    }

    public ReindexJob getJob(String jobId) {
        return jobs.get(jobId);
    }
}
