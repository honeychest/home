package com.chs.springboot.domain.chatbot.service;

import com.chs.springboot.domain.chatbot.config.ChatbotProperties;
import com.chs.springboot.domain.chatbot.service.GitNexusBoundaryProvider.SymbolBoundary;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SymbolAwareChunkerTest {

    private static final String SOURCE = "springboot/src/Foo.java";

    // 메서드 a(5~7), 메서드 b(8~10) 를 가진 가짜 자바 파일.
    private static final String CONTENT = String.join("\n",
            "package com.x;",          // 1
            "",                         // 2
            "public class Foo {",       // 3
            "    int field = 1;",       // 4
            "    void a() {",           // 5
            "        doA();",           // 6
            "    }",                     // 7
            "    void b() {",           // 8
            "        doB();",           // 9
            "    }",                     // 10
            "}");                        // 11

    private ChatbotProperties symbolAwareProps() {
        ChatbotProperties props = new ChatbotProperties();
        ChatbotProperties.Reindex r = props.getReindex();
        r.setChunkStrategy(ChatbotProperties.ChunkStrategy.SYMBOL_AWARE);
        r.setChunkSize(32);
        r.setMinChunkSizeChars(10);
        r.setOverlapTokens(0);
        r.setGitnexusRepo("lab");
        return props;
    }

    private Document fooDocument() {
        return new Document(CONTENT, Map.of("source", SOURCE));
    }

    @Test
    void 경계가_있으면_심볼_단위로_청킹된다() {
        GitNexusBoundaryProvider provider = mock(GitNexusBoundaryProvider.class);
        when(provider.loadBoundaries(anyString())).thenReturn(Map.of(
                SOURCE, List.of(
                        new SymbolBoundary(5, 7, "a"),
                        new SymbolBoundary(8, 10, "b"))));
        SymbolAwareChunker chunker = new SymbolAwareChunker(symbolAwareProps(), provider);

        List<Document> chunks = chunker.chunk(List.of(fooDocument()));

        assertThat(chunks).isNotEmpty();
        // 심볼 기반 경로를 탔으면 chunkStrategy 메타데이터가 SYMBOL_AWARE 여야 한다.
        assertThat(chunks).allSatisfy(c ->
                assertThat(c.getMetadata()).containsEntry("chunkStrategy", "SYMBOL_AWARE"));
        // 메서드 a, b 가 심볼 메타데이터로 반영돼야 한다.
        String symbols = chunks.stream()
                .map(c -> String.valueOf(c.getMetadata().getOrDefault("symbol", "")))
                .reduce("", (x, y) -> x + "," + y);
        assertThat(symbols).contains("a").contains("b");
        // 원본 source 메타데이터는 보존돼야 한다.
        assertThat(chunks).allSatisfy(c ->
                assertThat(c.getMetadata()).containsEntry("source", SOURCE));
    }

    @Test
    void 경계가_없으면_토큰_분할로_폴백한다() {
        GitNexusBoundaryProvider provider = mock(GitNexusBoundaryProvider.class);
        when(provider.loadBoundaries(anyString())).thenReturn(Map.of()); // 빈 맵

        SymbolAwareChunker chunker = new SymbolAwareChunker(symbolAwareProps(), provider);

        List<Document> chunks = chunker.chunk(List.of(fooDocument()));

        assertThat(chunks).isNotEmpty();
        // 폴백이면 SYMBOL_AWARE 메타데이터가 붙지 않는다.
        assertThat(chunks).noneSatisfy(c ->
                assertThat(c.getMetadata()).containsKey("chunkStrategy"));
    }

    @Test
    void 경계가_파일길이를_넘으면_stale로_보고_폴백한다() {
        GitNexusBoundaryProvider provider = mock(GitNexusBoundaryProvider.class);
        when(provider.loadBoundaries(anyString())).thenReturn(Map.of(
                SOURCE, List.of(new SymbolBoundary(5, 9999, "a")))); // 끝줄이 파일 범위 초과
        SymbolAwareChunker chunker = new SymbolAwareChunker(symbolAwareProps(), provider);

        List<Document> chunks = chunker.chunk(List.of(fooDocument()));

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).noneSatisfy(c ->
                assertThat(c.getMetadata()).containsKey("chunkStrategy"));
    }

    @Test
    void source_메타데이터가_없으면_폴백한다() {
        GitNexusBoundaryProvider provider = mock(GitNexusBoundaryProvider.class);
        when(provider.loadBoundaries(anyString())).thenReturn(Map.of(
                SOURCE, List.of(new SymbolBoundary(5, 7, "a"))));
        SymbolAwareChunker chunker = new SymbolAwareChunker(symbolAwareProps(), provider);

        Document noSource = new Document(CONTENT); // source 메타데이터 없음
        List<Document> chunks = chunker.chunk(List.of(noSource));

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).noneSatisfy(c ->
                assertThat(c.getMetadata()).containsKey("chunkStrategy"));
    }
}
