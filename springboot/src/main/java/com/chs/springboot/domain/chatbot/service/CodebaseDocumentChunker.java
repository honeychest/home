// [AGENT] 역할: 수집된 코드베이스 문서를 임베딩용 청크로 나누는 Adapter | 연관파일: AsyncReindexRunner.java
package com.chs.springboot.domain.chatbot.service;

import com.chs.springboot.domain.chatbot.config.ChatbotProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CodebaseDocumentChunker {

    private final ChatbotProperties properties;

    public CodebaseDocumentChunker(ChatbotProperties properties) {
        this.properties = properties;
    }

    public List<Document> chunk(List<Document> documents) {
        ChatbotProperties.Reindex reindex = properties.getReindex();
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
