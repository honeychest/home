// [AGENT] 역할: 코드베이스 색인 실제 실행(비동기 풀 리빌드) | 연관파일: CodebaseIndexingService.java, ReindexJob.java, PgVectorConfig.java
package com.chs.springboot.domain.chatbot.service;

import com.chs.springboot.domain.chatbot.dto.ReindexJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 색인 실제 실행부.
 *
 * 왜 별도 빈인가:
 *   - @Async 는 프록시 기반이라 "같은 클래스 내부 호출"이면 무시되어 동기 실행된다.
 *   - 그래서 비동기 실행 메서드를 호출하는 쪽(CodebaseIndexingService)과 분리해,
 *     반드시 프록시를 경유하도록 했다.
 *
 * 락 해제는 호출자가 넘긴 onComplete 콜백으로 처리(순환참조 회피).
 */
@Component
public class AsyncReindexRunner {

    private static final Logger log = LoggerFactory.getLogger(AsyncReindexRunner.class);

    private final CodebaseDocumentSource documentSource;
    private final CodebaseDocumentChunker documentChunker;
    private final VectorIndexWriter indexWriter;

    public AsyncReindexRunner(CodebaseDocumentSource documentSource,
                              CodebaseDocumentChunker documentChunker,
                              VectorIndexWriter indexWriter) {
        this.documentSource = documentSource;
        this.documentChunker = documentChunker;
        this.indexWriter = indexWriter;
    }

    @Async
    public void run(ReindexJob job, Runnable onComplete) {
        try {
            log.info("[색인 시작] jobId={}", job.getJobId());

            indexWriter.clear();
            List<Document> documents = documentSource.collect();
            log.info("[색인] 수집된 파일 수: {}", documents.size());

            List<Document> chunks = documentChunker.chunk(documents);
            log.info("[색인] 청킹 완료, 총 청크 수: {}", chunks.size());

            job.setTotalChunks(chunks.size());
            indexWriter.write(chunks, job::setProcessedChunks);

            job.markCompleted(chunks.size());
            log.info("[색인 완료] jobId={}, 청크 수={}", job.getJobId(), chunks.size());

        } catch (Exception e) {
            job.markFailed(e.getMessage());
            log.error("[색인 실패] jobId={}, 오류={}", job.getJobId(), e.getMessage(), e);
        } finally {
            onComplete.run();
        }
    }
}
