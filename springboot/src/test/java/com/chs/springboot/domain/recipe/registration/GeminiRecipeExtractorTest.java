// [AGENT] Gemini 추출 판정 고정 — 실 Gemini 없이 MockRestServiceServer 로 검증 (PLAYBOOK 관례 4)
package com.chs.springboot.domain.recipe.registration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GeminiRecipeExtractorTest {

    private MockRestServiceServer server;
    private GeminiRecipeExtractor extractor;

    @BeforeEach
    void setUp() {
        GikkaMediaProperties properties = new GikkaMediaProperties();
        properties.setGeminiApiKey("test-key");
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        // 토큰이 비어 있으므로 notify 는 무발송 — 테스트에서 외부 호출 없음
        GikkaTelegramNotifier notifier = new GikkaTelegramNotifier(RestClient.builder(), properties);
        extractor = new GeminiRecipeExtractor(builder, properties, notifier);
    }

    /** Gemini 응답 봉투: candidates[0].content.parts[0].text 안에 JSON 문자열 */
    private void geminiReturns(String innerJson) {
        String envelope = """
                {"candidates":[{"content":{"parts":[{"text":%s}]}}]}
                """.formatted(com.fasterxml.jackson.databind.node.TextNode.valueOf(innerJson).toString());
        server.expect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess(envelope, MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("요리 영상: 분류 RECIPE + 재료·단계 추출")
    void extractsRecipe() {
        geminiReturns("""
                {"category":"RECIPE","name":"두부조림","ingredients":["두부","간장","대파"],
                 "cookMinutes":15,"steps":["두부를 썬다","조린다"]}
                """);

        var result = extractor.extract("https://www.youtube.com/shorts/abc");

        assertEquals("RECIPE", result.category());
        assertEquals("두부조림", result.name());
        assertEquals(List.of("두부", "간장", "대파"), result.ingredients());
        assertEquals(15, result.cookMinutes());
        assertEquals(2, result.steps().size());
    }

    @Test
    @DisplayName("생활팁 영상: 분류만 TIP, 레시피 필드는 null (분류만 저장 — 확정 결정)")
    void classifiesTipOnly() {
        geminiReturns("""
                {"category":"TIP"}
                """);

        var result = extractor.extract("https://www.youtube.com/shorts/abc");

        assertEquals("TIP", result.category());
        assertNull(result.name());
        assertNull(result.ingredients());
    }

    @Test
    @DisplayName("429 는 RateLimitedException — 워커가 대기 후 자동 재개 (재시도 소모 없음)")
    void rateLimitBecomesTypedException() {
        server.expect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThrows(RecipeExtractor.RateLimitedException.class,
                () -> extractor.extract("https://www.youtube.com/shorts/abc"));
    }

    @Test
    @DisplayName("응답이 JSON 이 아니면 실패로 처리 (3회 후 FAILED 경로)")
    void brokenResponseFails() {
        geminiReturns("이것은 JSON 이 아님");

        assertThrows(IllegalStateException.class,
                () -> extractor.extract("https://www.youtube.com/shorts/abc"));
    }

    @Test
    @DisplayName("모델 404: 폴백 모델로 즉시 재시도하고, 이후 호출도 폴백을 유지 (페일오버)")
    void modelGoneFailsOverToFallback() {
        // 기대 순서: 설정 모델 404 → 같은 extract 안에서 폴백 재시도 성공
        // → 다음 extract 는 처음부터 폴백 사용 (404 낭비 없음)
        server.expect(requestTo(containsString("gemini-3.5-flash")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        expectFallbackSuccess();
        expectFallbackSuccess();

        assertEquals("TIP", extractor.extract("https://www.youtube.com/shorts/abc").category());
        assertEquals("TIP", extractor.extract("https://www.youtube.com/shorts/def").category());

        server.verify();
    }

    @Test
    @DisplayName("폴백 모델까지 404 면 일반 실패 경로로 (무한 전환 없음)")
    void fallbackAlsoGoneFails() {
        server.expect(requestTo(containsString("gemini-3.5-flash")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(containsString("gemini-flash-latest")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThrows(org.springframework.web.client.HttpClientErrorException.NotFound.class,
                () -> extractor.extract("https://www.youtube.com/shorts/abc"));
        server.verify();
    }

    @Test
    @DisplayName("봉투 파싱은 순수 함수 — HTTP 없이 직접 검증 (candidates[0]…parts[0].text 안의 JSON)")
    void parsesEnvelopeWithoutHttp() throws Exception {
        var envelope = new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                {"candidates":[{"content":{"parts":[{"text":"{\\"category\\":\\"RECIPE\\",\\"name\\":\\"김치찌개\\",\\"ingredients\\":[\\"김치\\"],\\"steps\\":[\\"끓인다\\"]}"}]}}]}
                """);

        var result = GeminiRecipeExtractor.parseEnvelope(envelope);

        assertEquals("RECIPE", result.category());
        assertEquals("김치찌개", result.name());
        assertEquals(List.of("김치"), result.ingredients());
        assertNull(result.cookMinutes());
    }

    private void expectFallbackSuccess() {
        String envelope = """
                {"candidates":[{"content":{"parts":[{"text":"{\\"category\\":\\"TIP\\"}"}]}}]}
                """;
        server.expect(requestTo(containsString("gemini-flash-latest")))
                .andRespond(withSuccess(envelope, MediaType.APPLICATION_JSON));
    }
}
