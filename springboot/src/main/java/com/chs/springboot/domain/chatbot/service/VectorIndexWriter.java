// [AGENT] 역할: PGVector 저장소 full rebuild 쓰기 Adapter | 연관파일: AsyncReindexRunner.java, PgVectorConfig.java
package com.chs.springboot.domain.chatbot.service;

import com.chs.springboot.domain.chatbot.config.ChatbotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.IntConsumer;

@Component
public class VectorIndexWriter {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexWriter.class);

    private final VectorStore vectorStore;
    private final JdbcTemplate pgVectorJdbcTemplate;
    private final ChatbotProperties properties;

    public VectorIndexWriter(VectorStore vectorStore,
                             @Qualifier("pgVectorJdbcTemplate") JdbcTemplate pgVectorJdbcTemplate,
                             ChatbotProperties properties) {
        this.vectorStore = vectorStore;
        this.pgVectorJdbcTemplate = pgVectorJdbcTemplate;
        this.properties = properties;
    }

    public void clear() {
        pgVectorJdbcTemplate.update("DELETE FROM vector_store");
        log.info("[색인] 기존 벡터 삭제 완료");
    }

    /** doc 레이어(생성 문서)만 삭제. 소스코드 벡터는 보존(증분 색인용). */
    public void clearDocs() {
        int deleted = pgVectorJdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'layer' = 'doc'");
        log.info("[색인] doc 레이어 벡터 삭제 완료: {}건", deleted);
    }

    public void write(List<Document> chunks, IntConsumer progress) {
        int batchSize = properties.getReindex().getBatchSize();
        int totalBatches = (chunks.size() + batchSize - 1) / batchSize;
        for (int i = 0; i < chunks.size(); i += batchSize) {
            List<Document> batch = chunks.subList(i, Math.min(i + batchSize, chunks.size()));
            vectorStore.add(batch);
            int processed = Math.min(i + batchSize, chunks.size());
            progress.accept(processed);
            log.info("[색인] 배치 {}/{} 완료, 누적 청크 {}/{}",
                    (i / batchSize) + 1, totalBatches, processed, chunks.size());
        }
    }
}
