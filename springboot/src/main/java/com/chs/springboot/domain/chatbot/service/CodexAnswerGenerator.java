// [AGENT] 역할: Codex 답변 프롬프트 생성 후 external runner 에 위임하는 Adapter | 연관파일: ChatbotService.java, CodexRunnerClient.java
package com.chs.springboot.domain.chatbot.service;

import com.chs.springboot.domain.chatbot.config.ChatbotProperties;
import com.chs.springboot.domain.chatbot.dto.ChatRequest;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CodexAnswerGenerator {

    private static final int MAX_EVIDENCE_CHARS = 12_000;
    private static final int MAX_HISTORY_CHARS = 6_000;

    private final ChatbotProperties properties;
    private final CodexRunnerClient codexRunnerClient;

    public CodexAnswerGenerator(ChatbotProperties properties, CodexRunnerClient codexRunnerClient) {
        this.properties = properties;
        this.codexRunnerClient = codexRunnerClient;
    }

    public CodexRunResult generate(String question, String searchQuery, List<ChatRequest.Turn> history,
                                   String pageContext, RetrievedEvidence evidence) {
        return codexRunnerClient.run(new CodexRunRequest(
                prompt(question, searchQuery, history, pageContext, evidence),
                properties.getModel().getCodexTimeoutSeconds()
        ));
    }

    public CodexRunResult healthCheck() {
        return codexRunnerClient.run(new CodexRunRequest("Say OK only.", properties.getModel().getCodexTimeoutSeconds()));
    }

    private String prompt(String question, String searchQuery, List<ChatRequest.Turn> history,
                          String pageContext, RetrievedEvidence evidence) {
        return """
                너는 이 프로젝트 코드베이스에 대한 질문에 답하는 도우미다.
                아래 근거와 이전 대화 맥락을 사용해 한국어로 간결하게 답하라.
                코드/프로젝트 사실은 근거 또는 읽은 저장소 내용에 있는 것만 말하고 지어내지 마라.
                근거 파일 경로는 본문에 적지 마라. 모호하면 한 번 되물어라.

                [현재 화면]
                %s

                [이전 대화]
                %s

                [검색 질의]
                %s

                [근거]
                %s

                [질문]
                %s
                """.formatted(
                blankToNone(pageContext),
                historyText(history),
                blankToNone(searchQuery),
                evidenceText(evidence),
                blankToNone(question)
        );
    }

    private String historyText(List<ChatRequest.Turn> history) {
        if (history == null || history.isEmpty()) {
            return "(없음)";
        }
        StringBuilder sb = new StringBuilder();
        for (ChatRequest.Turn turn : history) {
            if (turn == null || turn.content() == null || turn.content().isBlank()) {
                continue;
            }
            sb.append(turn.role()).append(": ").append(turn.content().trim()).append("\n");
            if (sb.length() >= MAX_HISTORY_CHARS) {
                break;
            }
        }
        return limit(sb.toString(), MAX_HISTORY_CHARS);
    }

    private String evidenceText(RetrievedEvidence evidence) {
        if (evidence == null || evidence.documents() == null || evidence.documents().isEmpty()) {
            return "(없음)";
        }
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (Document document : evidence.documents()) {
            String source = String.valueOf(document.getMetadata().getOrDefault("source", "(unknown)"));
            sb.append("근거 ").append(index++).append(" - ").append(source).append("\n");
            sb.append(document.getText()).append("\n\n");
            if (sb.length() >= MAX_EVIDENCE_CHARS) {
                break;
            }
        }
        return limit(sb.toString(), MAX_EVIDENCE_CHARS);
    }

    private String limit(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private String blankToNone(String value) {
        return value == null || value.isBlank() ? "(없음)" : value;
    }
}
