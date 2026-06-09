// [AGENT] 역할: 색인 대상 파일을 Spring AI Document로 수집하는 Adapter | 연관파일: AsyncReindexRunner.java
package com.chs.springboot.domain.chatbot.service;

import com.chs.springboot.domain.chatbot.config.ChatbotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class CodebaseDocumentSource {

    private static final Logger log = LoggerFactory.getLogger(CodebaseDocumentSource.class);

    private final ChatbotProperties properties;

    public CodebaseDocumentSource(ChatbotProperties properties) {
        this.properties = properties;
    }

    public List<Document> collect() throws Exception {
        // source 메타데이터 상대화 기준(예: 레포 루트). 미설정 시 각 루트 기준으로 상대화.
        Path base = properties.getSourceBase() != null ? Paths.get(properties.getSourceBase()) : null;
        List<Document> documents = new ArrayList<>();

        for (String rootStr : properties.getIndexRoots()) {
            Path root = Paths.get(rootStr);
            if (!Files.isDirectory(root)) {
                log.warn("[색인] 색인 루트가 디렉토리가 아님, 건너뜀: {}", root);
                continue;
            }
            try (var stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile)
                      .filter(this::isIncluded)
                      .forEach(p -> readDocument(base, root, p, documents));
            }
        }
        return documents;
    }

    private boolean isIncluded(Path path) {
        String name = path.getFileName().toString();
        return properties.getReindex().getIncludeExtensions().stream().anyMatch(name::endsWith);
    }

    private void readDocument(Path base, Path root, Path path, List<Document> documents) {
        try {
            String content = Files.readString(path);
            if (content.isBlank()) {
                return;
            }
            // base 하위면 base 기준(예: "frontend/src/..."), 아니면 해당 루트 기준으로 상대화.
            Path relativeFrom = (base != null && path.startsWith(base)) ? base : root;
            String relativePath = relativeFrom.relativize(path).toString();
            documents.add(new Document(content, Map.of("source", relativePath)));
        } catch (Exception e) {
            log.warn("[색인] 파일 읽기 실패, 건너뜀: {} | 원인: {}", path, e.getMessage());
        }
    }
}
