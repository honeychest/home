package com.chs.springboot.domain.chatbot.service;

import com.chs.springboot.domain.chatbot.config.ChatbotProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalCodexRunnerClientTest {

    @Test
    @DisplayName("external runner 응답 success 는 Codex 성공 결과로 매핑한다")
    void run_mapsSuccessResponse() throws Exception {
        HttpServer server = startServer(200, "{\"status\":\"success\",\"answer\":\"OK\",\"stdout\":\"out\"}", 0);
        try {
            ExternalCodexRunnerClient client = new ExternalCodexRunnerClient(properties(server), new ObjectMapper());

            CodexRunResult result = client.run(new CodexRunRequest("Say OK only.", 2));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.answer()).isEqualTo("OK");
            assertThat(result.stdout()).isEqualTo("out");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("external runner URL 이 없으면 codex_not_available 로 반환한다")
    void run_returnsNotAvailableWhenUrlMissing() {
        ExternalCodexRunnerClient client = new ExternalCodexRunnerClient(new ChatbotProperties(), new ObjectMapper());

        CodexRunResult result = client.run(new CodexRunRequest("Say OK only.", 1));

        assertThat(result.status()).isEqualTo(CodexRunStatus.CODEX_NOT_AVAILABLE);
    }

    @Test
    @DisplayName("external runner 가 timeout 안에 응답하지 않으면 timeout 으로 반환한다")
    void run_returnsTimeoutWhenRunnerDoesNotRespondInTime() throws Exception {
        HttpServer server = startServer(200, "{\"status\":\"success\",\"answer\":\"OK\"}", 2_000);
        try {
            ExternalCodexRunnerClient client = new ExternalCodexRunnerClient(properties(server), new ObjectMapper());

            CodexRunResult result = client.run(new CodexRunRequest("Say OK only.", 1));

            assertThat(result.status()).isEqualTo(CodexRunStatus.TIMEOUT);
        } finally {
            server.stop(0);
        }
    }

    private ChatbotProperties properties(HttpServer server) {
        ChatbotProperties properties = new ChatbotProperties();
        properties.getModel().setCodexRunnerUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getModel().setCodexTimeoutSeconds(1);
        return properties;
    }

    private HttpServer startServer(int statusCode, String body, long delayMs) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/codex/run", exchange -> {
            try {
                if (delayMs > 0) {
                    Thread.sleep(delayMs);
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(statusCode, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
        return server;
    }
}
