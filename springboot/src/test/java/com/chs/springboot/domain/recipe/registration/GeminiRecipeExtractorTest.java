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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
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
        // Gemini 호출 봉투·일시적 실패 매핑은 GeminiJsonClient seam 소유 (2026-07-17 점검) — mock 서버는
        // 그 client 의 builder 에 바인딩. 추출기의 HTTP 레벨 기대(모델 URL·429/503·페일오버)는 그대로 통과.
        extractor = new GeminiRecipeExtractor(new GeminiJsonClient(builder, properties), properties, notifier);
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
                 "cookMinutes":15,"steps":["두부를 썬다","조린다"],"tags":["두부조림","두부","밑반찬"]}
                """);

        var result = extractor.extract("https://www.youtube.com/shorts/abc", null, null);

        assertEquals("RECIPE", result.category());
        assertEquals("두부조림", result.name());
        assertEquals(List.of("두부", "간장", "대파"), result.ingredients());
        assertEquals(15, result.cookMinutes());
        assertEquals(2, result.steps().size());
        assertEquals(List.of("두부조림", "두부", "밑반찬"), result.tags()); // 태그는 전 분류 공통
        assertNull(result.summary()); // RECIPE 요약은 name·steps 가 대신함
    }

    @Test
    @DisplayName("설명란(본문)이 있으면 요청 본문에 별도 텍스트로 실어 보낸다 (2026-07-13 확정 — 재료 원문 최우선 활용)")
    void includesDescriptionWhenPresent() {
        server.expect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(content().string(containsString("영상 설명란 원문")))
                .andExpect(content().string(containsString("재료: 두부 1모, 대파 1대")))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"parts":[{"text":"{\\"category\\":\\"TIP\\"}"}]}}]}
                        """, MediaType.APPLICATION_JSON));

        extractor.extract("https://www.youtube.com/shorts/abc", null, "재료: 두부 1모, 대파 1대");

        server.verify();
    }

    @Test
    @DisplayName("설명란이 없으면(null) 요청 본문에 그 텍스트 파트를 안 붙인다")
    void omitsDescriptionPartWhenAbsent() {
        server.expect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("영상 설명란 원문"))))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"parts":[{"text":"{\\"category\\":\\"TIP\\"}"}]}}]}
                        """, MediaType.APPLICATION_JSON));

        extractor.extract("https://www.youtube.com/shorts/abc", null, null);

        server.verify();
    }

    @Test
    @DisplayName("생활팁 영상: 레시피 필드는 null, 요약·검색 태그가 채워짐 (2026-07-13 확정)")
    void classifiesTipWithSummaryAndTags() {
        geminiReturns("""
                {"category":"TIP","summary":"운동화 끈이 안 풀리게 묶는 법을 보여준다. 매듭을 두 번 감아 고정한다.",
                 "tags":["신발끈","매듭","운동화"]}
                """);

        var result = extractor.extract("https://www.youtube.com/shorts/abc", null, null);

        assertEquals("TIP", result.category());
        assertNull(result.name());
        assertNull(result.ingredients());
        assertEquals("운동화 끈이 안 풀리게 묶는 법을 보여준다. 매듭을 두 번 감아 고정한다.", result.summary());
        assertEquals(List.of("신발끈", "매듭", "운동화"), result.tags());
    }

    @Test
    @DisplayName("요약·태그 없는 구형 응답도 안전 (summary null, tags 빈 목록)")
    void tolerantWhenSummaryAndTagsMissing() {
        geminiReturns("""
                {"category":"TIP"}
                """);

        var result = extractor.extract("https://www.youtube.com/shorts/abc", null, null);

        assertEquals("TIP", result.category());
        assertNull(result.summary());
        assertEquals(List.of(), result.tags());
    }

    @Test
    @DisplayName("429 는 TransientFailureException — 워커가 대기 후 자동 재개 (재시도 소모 없음)")
    void rateLimitBecomesTypedException() {
        server.expect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThrows(RecipeExtractor.TransientFailureException.class,
                () -> extractor.extract("https://www.youtube.com/shorts/abc", null, null));
    }

    @Test
    @DisplayName("503(과부하)도 TransientFailureException — 429와 동일 취급 (2026-07-13 실측 반영)")
    void serviceUnavailableBecomesTypedException() {
        server.expect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThrows(RecipeExtractor.TransientFailureException.class,
                () -> extractor.extract("https://www.youtube.com/shorts/abc", null, null));
    }

    @Test
    @DisplayName("타임아웃(I/O 오류)도 TransientFailureException (2026-07-13 실측 반영)")
    void timeoutBecomesTypedException() {
        server.expect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(request -> { throw new java.io.IOException("Request timed out"); });

        assertThrows(RecipeExtractor.TransientFailureException.class,
                () -> extractor.extract("https://www.youtube.com/shorts/abc", null, null));
    }

    @Test
    @DisplayName("응답이 JSON 이 아니면 실패로 처리 (3회 후 FAILED 경로)")
    void brokenResponseFails() {
        geminiReturns("이것은 JSON 이 아님");

        assertThrows(IllegalStateException.class,
                () -> extractor.extract("https://www.youtube.com/shorts/abc", null, null));
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

        assertEquals("TIP", extractor.extract("https://www.youtube.com/shorts/abc", null, null).category());
        assertEquals("TIP", extractor.extract("https://www.youtube.com/shorts/def", null, null).category());

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
                () -> extractor.extract("https://www.youtube.com/shorts/abc", null, null));
        server.verify();
    }

    private void expectFallbackSuccess() {
        String envelope = """
                {"candidates":[{"content":{"parts":[{"text":"{\\"category\\":\\"TIP\\"}"}]}}]}
                """;
        server.expect(requestTo(containsString("gemini-flash-latest")))
                .andRespond(withSuccess(envelope, MediaType.APPLICATION_JSON));
    }
}
