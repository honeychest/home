// [AGENT] 로컬 우선 라우팅 고정 (2026-07-14 grill-me 확정) — 실 네트워크 없이 두 MockRestServiceServer 로 검증
package com.chs.springboot.domain.recipe.registration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HybridRecipeExtractorTest {

    private MockRestServiceServer localServer;
    private MockRestServiceServer geminiServer;
    private HybridRecipeExtractor hybrid;

    @BeforeEach
    void setUp() {
        GikkaMediaProperties properties = new GikkaMediaProperties();
        properties.setLocalExtractorBaseUrl("http://gikka-local.test");
        properties.setGeminiApiKey("test-key");

        RestClient.Builder localBuilder = RestClient.builder();
        localServer = MockRestServiceServer.bindTo(localBuilder).build();
        LocalRecipeExtractor local = new LocalRecipeExtractor(localBuilder, properties);

        RestClient.Builder geminiBuilder = RestClient.builder();
        geminiServer = MockRestServiceServer.bindTo(geminiBuilder).build();
        GikkaTelegramNotifier notifier = new GikkaTelegramNotifier(RestClient.builder(), properties);
        GeminiRecipeExtractor gemini = new GeminiRecipeExtractor(
                new GeminiJsonClient(geminiBuilder, properties), properties, notifier);

        hybrid = new HybridRecipeExtractor(local, gemini);
    }

    @Test
    @DisplayName("로컬이 RECIPE 로 분류하면 로컬 결과를 그대로 채택 — Gemini 호출 안 함")
    void keepsLocalResultWhenRecipe() {
        localServer.expect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"category":"RECIPE","name":"참치무조림","ingredients":["무","참치"],"steps":["끓인다"]}
                        """, MediaType.APPLICATION_JSON));

        var result = hybrid.extract("https://www.youtube.com/watch?v=abc", null, null);

        assertEquals("RECIPE", result.category());
        assertEquals("참치무조림", result.name());
        geminiServer.verify(); // 기대 등록이 없으므로 호출 없었음이 곧 검증
    }

    @Test
    @DisplayName("로컬이 TIP/ETC 로 분류하면 로컬 결과를 버리고 Gemini 로 다시 분석 (할루시네이션 위험 회피)")
    void fallsBackToGeminiWhenNotRecipe() {
        localServer.expect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"category":"ETC","summary":"로컬이 지어냈을 수 있는 요약"}
                        """, MediaType.APPLICATION_JSON));
        geminiServer.expect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"parts":[{"text":"{\\"category\\":\\"ETC\\",\\"summary\\":\\"Gemini 요약\\"}"}]}}]}
                        """, MediaType.APPLICATION_JSON));

        var result = hybrid.extract("https://www.youtube.com/watch?v=abc", null, null);

        assertEquals("ETC", result.category());
        assertEquals("Gemini 요약", result.summary());
    }

    @Test
    @DisplayName("로컬 서비스가 죽어있으면(500) 전체를 Gemini 로 폴백")
    void fallsBackToGeminiWhenLocalUnavailable() {
        localServer.expect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        geminiServer.expect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"parts":[{"text":"{\\"category\\":\\"RECIPE\\",\\"name\\":\\"두부조림\\",\\"ingredients\\":[\\"두부\\"],\\"steps\\":[\\"조린다\\"]}"}]}}]}
                        """, MediaType.APPLICATION_JSON));

        var result = hybrid.extract("https://www.youtube.com/watch?v=abc", null, null);

        assertEquals("RECIPE", result.category());
        assertEquals("두부조림", result.name());
    }

    @Test
    @DisplayName("로컬이 TIP/ETC 로 성공했는데 Gemini 검증이 일시적으로 실패(429)하면 로컬 결과를 그대로 채택")
    void keepsLocalResultWhenGeminiTransientFailureAfterLocalSuccess() {
        localServer.expect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"category":"TIP","summary":"로컬 요약","tags":["신발끈"]}
                        """, MediaType.APPLICATION_JSON));
        geminiServer.expect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        var result = hybrid.extract("https://www.youtube.com/watch?v=abc", null, null);

        assertEquals("TIP", result.category());
        assertEquals("로컬 요약", result.summary());
    }

    @Test
    @DisplayName("로컬 자체가 불가한데 Gemini 마저 일시적으로 실패하면 대체할 결과가 없어 예외를 그대로 던짐")
    void rethrowsTransientFailureWhenNoLocalFallback() {
        localServer.expect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        geminiServer.expect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        org.junit.jupiter.api.Assertions.assertThrows(
                RecipeExtractor.TransientFailureException.class,
                () -> hybrid.extract("https://www.youtube.com/watch?v=abc", null, null));
    }
}
