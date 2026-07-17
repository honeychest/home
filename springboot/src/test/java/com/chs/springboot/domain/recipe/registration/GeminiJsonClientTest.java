// [AGENT] Gemini 호출 seam 고정 (2026-07-17 점검에서 추출) — 봉투 언랩은 순수(HTTP 없음),
// 일시적 실패 매핑은 MockRestServiceServer. 이 규칙이 seam 에 있으므로 추출기·감사기 두 호출자가
// 동일하게 받는다(이전엔 감사기에 매핑이 빠져 있었음 — 그 어긋남을 seam 통합으로 해소).
package com.chs.springboot.domain.recipe.registration;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class GeminiJsonClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("봉투 언랩: candidates[0]…parts[0].text 안의 JSON 알맹이를 꺼낸다 (순수, HTTP 없음)")
    void unwrapExtractsInnerJson() throws Exception {
        var envelope = MAPPER.readTree("""
                {"candidates":[{"content":{"parts":[{"text":"{\\"category\\":\\"RECIPE\\",\\"name\\":\\"김치찌개\\"}"}]}}]}
                """);

        var inner = GeminiJsonClient.unwrap(envelope);

        assertEquals("RECIPE", inner.path("category").asText());
        assertEquals("김치찌개", inner.path("name").asText());
    }

    @Test
    @DisplayName("알맹이가 JSON 이 아니면 IllegalStateException (일반 실패 경로)")
    void unwrapFailsOnBrokenInner() throws Exception {
        var envelope = MAPPER.readTree("""
                {"candidates":[{"content":{"parts":[{"text":"JSON 이 아님"}]}}]}
                """);

        assertThrows(IllegalStateException.class, () -> GeminiJsonClient.unwrap(envelope));
    }

    @Test
    @DisplayName("429 는 TransientFailureException — seam 이 매핑하므로 추출기·감사기 두 호출자가 공유")
    void rateLimitBecomesTransient() {
        GikkaMediaProperties properties = new GikkaMediaProperties();
        properties.setGeminiApiKey("test-key");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.POST)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        GeminiJsonClient client = new GeminiJsonClient(builder, properties);

        assertThrows(RecipeExtractor.TransientFailureException.class,
                () -> client.generate("model", List.of(Map.of("text", "x")), Map.of()));
    }
}
