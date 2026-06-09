package com.chs.springboot.domain.chatbot.service;

import com.chs.springboot.domain.chatbot.config.ChatbotProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodebaseDocumentChunkerTest {

    private Document sample() {
        return new Document("public class A {}\n", Map.of("source", "springboot/src/A.java"));
    }

    @Test
    void TOKEN_전략이면_심볼청커를_호출하지_않는다() {
        ChatbotProperties props = new ChatbotProperties();
        props.getReindex().setChunkStrategy(ChatbotProperties.ChunkStrategy.TOKEN);
        SymbolAwareChunker symbolAware = mock(SymbolAwareChunker.class);
        CodebaseDocumentChunker chunker = new CodebaseDocumentChunker(props, symbolAware);

        List<Document> chunks = chunker.chunk(List.of(sample()));

        assertThat(chunks).isNotEmpty();
        verify(symbolAware, never()).chunk(anyList());
    }

    @Test
    void SYMBOL_AWARE_전략이면_심볼청커에_위임한다() {
        ChatbotProperties props = new ChatbotProperties();
        props.getReindex().setChunkStrategy(ChatbotProperties.ChunkStrategy.SYMBOL_AWARE);
        SymbolAwareChunker symbolAware = mock(SymbolAwareChunker.class);
        List<Document> expected = List.of(sample());
        when(symbolAware.chunk(anyList())).thenReturn(expected);
        CodebaseDocumentChunker chunker = new CodebaseDocumentChunker(props, symbolAware);

        List<Document> chunks = chunker.chunk(List.of(sample()));

        assertThat(chunks).isSameAs(expected);
        verify(symbolAware, times(1)).chunk(anyList());
    }
}
