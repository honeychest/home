// [AGENT] 역할: 외부/호스트 Codex runner API 클라이언트 | 연관파일: CodexRunnerClient.java, ChatbotProperties.java
package com.chs.springboot.domain.chatbot.service;

import com.chs.springboot.domain.chatbot.config.ChatbotProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

@Component
public class ExternalCodexRunnerClient implements CodexRunnerClient {

    private final ChatbotProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ExternalCodexRunnerClient(ChatbotProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @Override
    public CodexRunResult run(CodexRunRequest request) {
        ChatbotProperties.Model model = properties.getModel();
        if (!"external".equalsIgnoreCase(model.getCodexRunnerMode())) {
            return CodexRunResult.failure(CodexRunStatus.CODEX_NOT_AVAILABLE,
                    "Codex runner mode is not external.");
        }
        if (model.getCodexRunnerUrl() == null || model.getCodexRunnerUrl().isBlank()) {
            return CodexRunResult.failure(CodexRunStatus.CODEX_NOT_AVAILABLE,
                    "Codex runner URL is not configured.");
        }

        int timeoutSeconds = Math.max(1, request.timeoutSeconds());
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "prompt", request.prompt(),
                    "timeoutSeconds", timeoutSeconds
            ));
            HttpRequest httpRequest = HttpRequest.newBuilder(runUri(model))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            return parseResponse(response);
        } catch (HttpTimeoutException e) {
            return CodexRunResult.failure(CodexRunStatus.TIMEOUT, "Codex runner request timed out.");
        } catch (ConnectException e) {
            return CodexRunResult.failure(CodexRunStatus.CODEX_NOT_AVAILABLE, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CodexRunResult.failure(CodexRunStatus.TIMEOUT, "Codex runner request was interrupted.");
        } catch (IOException | IllegalArgumentException e) {
            return CodexRunResult.failure(classifyError(e.getMessage()), e.getMessage());
        }
    }

    private URI runUri(ChatbotProperties.Model model) {
        return URI.create(join(model.getCodexRunnerUrl(), model.getCodexRunPath()));
    }

    private CodexRunResult parseResponse(HttpResponse<String> response) throws IOException {
        int statusCode = response.statusCode();
        String body = response.body() == null ? "" : response.body();
        if (statusCode == 408 || statusCode == 504) {
            return CodexRunResult.failure(CodexRunStatus.TIMEOUT, body);
        }
        if (statusCode == 401 || statusCode == 403) {
            return CodexRunResult.failure(CodexRunStatus.AUTH_REQUIRED, body);
        }
        if (statusCode == 404 || statusCode == 503) {
            return CodexRunResult.failure(CodexRunStatus.CODEX_NOT_AVAILABLE, body);
        }

        JsonNode root = body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
        CodexRunStatus runnerStatus = CodexRunStatus.fromWireValue(text(root, "status"));
        String answer = firstNonBlank(text(root, "answer"), text(root, "output"));
        String stdout = text(root, "stdout");
        String stderr = text(root, "stderr");
        String errorMessage = firstNonBlank(text(root, "errorMessage"), text(root, "error"), body);

        if (statusCode < 200 || statusCode >= 300) {
            return CodexRunResult.failure(classifyError(errorMessage), errorMessage);
        }
        if (runnerStatus == CodexRunStatus.SUCCESS && answer != null && !answer.isBlank()) {
            return CodexRunResult.success(answer, stdout, stderr);
        }
        return CodexRunResult.failure(runnerStatus == CodexRunStatus.SUCCESS
                ? CodexRunStatus.EXECUTION_FAILED
                : runnerStatus, errorMessage);
    }

    private CodexRunStatus classifyError(String message) {
        String normalized = message == null ? "" : message.toLowerCase();
        if (normalized.contains("timed out") || normalized.contains("timeout")) {
            return CodexRunStatus.TIMEOUT;
        }
        if (normalized.contains("auth") || normalized.contains("login") || normalized.contains("sign in")) {
            return CodexRunStatus.AUTH_REQUIRED;
        }
        if (normalized.contains("update") || normalized.contains("upgrade")) {
            return CodexRunStatus.UPDATE_REQUIRED;
        }
        if (normalized.contains("not found") || normalized.contains("connection refused")) {
            return CodexRunStatus.CODEX_NOT_AVAILABLE;
        }
        return CodexRunStatus.EXECUTION_FAILED;
    }

    private String join(String baseUrl, String path) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String suffix = path == null || path.isBlank() ? "/api/codex/run" : path;
        return base + (suffix.startsWith("/") ? suffix : "/" + suffix);
    }

    private String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
