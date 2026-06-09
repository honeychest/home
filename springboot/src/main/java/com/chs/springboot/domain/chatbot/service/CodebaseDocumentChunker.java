// [AGENT] 역할: 청킹 전략(TOKEN/SYMBOL_AWARE)을 분기하는 진입 Adapter | 연관파일: AsyncReindexRunner.java, SymbolAwareChunker.java
package com.chs.springboot.domain.chatbot.service;

import com.chs.springboot.domain.chatbot.config.ChatbotProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CodebaseDocumentChunker {

    private final ChatbotProperties properties;
    private final SymbolAwareChunker symbolAwareChunker;

    public CodebaseDocumentChunker(ChatbotProperties properties, SymbolAwareChunker symbolAwareChunker) {
        this.properties = properties;
        this.symbolAwareChunker = symbolAwareChunker;
    }

    public List<Document> chunk(List<Document> documents) {
        ChatbotProperties.Reindex reindex = properties.getReindex();
        // SYMBOL_AWARE 면 심볼 경계 청커로 위임(내부에서 파일별 토큰 폴백 처리).
        if (reindex.getChunkStrategy() == ChatbotProperties.ChunkStrategy.SYMBOL_AWARE) {
            return symbolAwareChunker.chunk(documents);
        }
        // 기본 TOKEN: 기존 방식 그대로.
        TokenTextSplitter splitter = new TokenTextSplitter(
                reindex.getChunkSize(),
                reindex.getMinChunkSizeChars(),
                reindex.getMinChunkLengthToEmbed(),
                reindex.getMaxNumChunks(),
                reindex.isKeepSeparator()
        );
        return splitter.apply(documents);
    }
}
