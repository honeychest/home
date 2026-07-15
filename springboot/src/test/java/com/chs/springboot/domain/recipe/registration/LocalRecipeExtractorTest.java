// [AGENT] 로컬 추출기 판정 고정 — 실 호스트 서비스 없이 MockRestServiceServer 로 검증 (PLAYBOOK 관례 4)
package com.chs.springboot.domain.recipe.registration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LocalRecipeExtractorTest {

    private MockRestServiceServer server;
    private LocalRecipeExtractor extractor;

    @BeforeEach
    void setUp() {
        GikkaMediaProperties properties = new GikkaMediaProperties();
        properties.setLocalExtractorBaseUrl("http://gikka-local.test");
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        extractor = new LocalRecipeExtractor(builder, properties);
    }

    @Test
    @DisplayName("요리 영상: 호스트 서비스가 돌려준 category/ingredients 를 그대로 파싱")
    void extractsRecipe() {
        server.expect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(requestTo("http://gikka-local.test/extract"))
                .andRespond(withSuccess("""
                        {"category":"RECIPE","name":"참치무조림","ingredients":["무","참치","고춧가루"],
                         "cookMinutes":20,"steps":["끓인다","넣는다"],"tags":["참치무조림"]}
                        """, MediaType.APPLICATION_JSON));

        var result = extractor.extract("https://www.youtube.com/watch?v=abc", null);

        assertEquals("RECIPE", result.category());
        assertEquals("참치무조림", result.name());
        assertEquals(List.of("무", "참치", "고춧가루"), result.ingredients());
        assertEquals(20, result.cookMinutes());
        assertNull(result.summary());
    }

    @Test
    @DisplayName("생활팁: 레시피 필드는 null, summary 만 채워짐")
    void classifiesNonRecipe() {
        server.expect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"category":"TIP","summary":"신발끈 묶는 법","tags":["신발끈"]}
                        """, MediaType.APPLICATION_JSON));

        var result = extractor.extract("https://www.youtube.com/watch?v=abc", null);

        assertEquals("TIP", result.category());
        assertNull(result.name());
        assertNull(result.ingredients());
        assertEquals("신발끈 묶는 법", result.summary());
    }

    @Test
    @DisplayName("호스트 서비스 실패(500) 는 LocalUnavailableException 으로 묶인다 — Hybrid 가 Gemini 로 폴백")
    void serviceFailureBecomesUnavailable() {
        server.expect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThrows(LocalRecipeExtractor.LocalUnavailableException.class,
                () -> extractor.extract("https://www.youtube.com/watch?v=abc", null));
    }

    @Test
    @DisplayName("비활성화 설정(local-extractor-enabled=false) 이면 호출 없이 즉시 unavailable")
    void disabledSkipsCall() {
        GikkaMediaProperties properties = new GikkaMediaProperties();
        properties.setLocalExtractorEnabled(false);
        LocalRecipeExtractor disabled = new LocalRecipeExtractor(RestClient.builder(), properties);

        assertThrows(LocalRecipeExtractor.LocalUnavailableException.class,
                () -> disabled.extract("https://www.youtube.com/watch?v=abc", null));
    }
}
