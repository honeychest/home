package com.chs.springboot.domain.chatbot.service;

import com.chs.springboot.domain.chatbot.config.ChatbotProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class VectorIndexWriterTest {

    @Mock
    private VectorStore vectorStore;

    @Mock
    private JdbcTemplate pgVectorJdbcTemplate;

    @Test
    @DisplayName("full rebuild는 기존 벡터를 지우고 설정된 배치 크기로 저장한다")
    void clearAndWrite_batchesChunks() {
        ChatbotProperties properties = new ChatbotProperties();
        properties.getReindex().setBatchSize(2);
        VectorIndexWriter writer = new VectorIndexWriter(vectorStore, pgVectorJdbcTemplate, properties);
        List<Integer> progress = new ArrayList<>();
        List<Document> chunks = List.of(
                doc("1"), doc("2"), doc("3"), doc("4"), doc("5")
        );

        writer.clear();
        writer.write(chunks, progress::add);

        verify(pgVectorJdbcTemplate).update("DELETE FROM vector_store");
        verify(vectorStore, times(3)).add(anyList());
        assertThat(progress).containsExactly(2, 4, 5);
    }

    @Test
    @DisplayName("도메인 증분 색인은 source 프리픽스에 해당하는 벡터만 삭제한다")
    void clearBySourcePrefixes_deletesMatchingSources() {
        ChatbotProperties properties = new ChatbotProperties();
        VectorIndexWriter writer = new VectorIndexWriter(vectorStore, pgVectorJdbcTemplate, properties);

        writer.clearBySourcePrefixes(List.of("springboot\\src\\main\\java\\com\\chs\\springboot\\domain\\analysis"));

        verify(pgVectorJdbcTemplate).update(
                "DELETE FROM vector_store WHERE (translate(metadata->>'source', chr(92), '/') = ? OR translate(metadata->>'source', chr(92), '/') LIKE ?)",
                "springboot/src/main/java/com/chs/springboot/domain/analysis",
                "springboot/src/main/java/com/chs/springboot/domain/analysis/%"
        );
    }

    private Document doc(String text) {
        return new Document(text, Map.of("source", text + ".java"));
    }
}
