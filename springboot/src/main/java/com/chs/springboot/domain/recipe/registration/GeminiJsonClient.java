// [AGENT] Gemini generateContent 호출 seam (2026-07-17 아키텍처 점검에서 추출) — 봉투 벗기기와
// 일시적 실패 매핑을 한 곳에. 이전엔 GeminiRecipeExtractor 와 IngredientAuditor 가 같은 엔드포인트·
// 봉투(candidates[0]…parts[0].text)·429/503/타임아웃 매핑을 각자 복제하고 있었고, 그 복제 때문에
// IngredientAuditor 는 일시적 실패 매핑이 빠져 조용히 다르게 동작했다(두 어댑터 = 실체 seam).
// 호출자는 프롬프트(parts)·generationConfig(스키마·해상도 등)·알맹이 파싱만 소유한다.
// 외부 HTTP 는 RestClient.Builder 주입 (PLAYBOOK 관례 4, pattern-rest-seam).
package com.chs.springboot.domain.recipe.registration;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Component
public class GeminiJsonClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiJsonClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient rest;
    private final GikkaMediaProperties properties;

    public GeminiJsonClient(RestClient.Builder builder, GikkaMediaProperties properties) {
        this.rest = builder.baseUrl("https://generativelanguage.googleapis.com").build();
        this.properties = properties;
    }

    /**
     * generateContent 호출 → 구조화 JSON 봉투(candidates[0]…parts[0].text)를 벗겨 알맹이 JsonNode 반환.
     * 일시적 실패(429·503·타임아웃)는 {@link RecipeExtractor.TransientFailureException} 으로 매핑한다.
     * 그 외 4xx(404 등)는 그대로 전파 — 호출자 정책(예: 추출기의 모델 폐쇄 페일오버)이 처리한다.
     * generationConfig 는 호출자가 소유(responseSchema·mediaResolution 등 호출마다 다름).
     */
    public JsonNode generate(String model, List<Map<String, Object>> parts,
                             Map<String, Object> generationConfig) {
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", parts)),
                "generationConfig", generationConfig);
        JsonNode response;
        try {
            response = rest.post()
                    .uri("/v1beta/models/{model}:generateContent?key={key}",
                            model, properties.getGeminiApiKey())
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                log.warn("Gemini 일시적 실패: {} - {}", "429 한도초과", e.getMessage());
                throw new RecipeExtractor.TransientFailureException(e.getMessage());
            }
            throw e; // 404(모델 폐쇄) 등은 호출자 정책으로
        } catch (HttpServerErrorException e) {
            // 503 "high demand" 등 Gemini 쪽 일시적 과부하 (2026-07-13 실측)
            log.warn("Gemini 일시적 실패: {} - {}", "503 과부하", e.getMessage());
            throw new RecipeExtractor.TransientFailureException(e.getMessage());
        } catch (ResourceAccessException e) {
            // 응답 지연 타임아웃 — 실측상 일시적 현상
            log.warn("Gemini 일시적 실패: {} - {}", "타임아웃", e.getMessage());
            throw new RecipeExtractor.TransientFailureException(e.getMessage());
        }
        return unwrap(response);
    }

    /** 봉투 언랩 — 순수(HTTP 없음). candidates[0].content.parts[0].text 안의 JSON 문자열을 파싱 */
    static JsonNode unwrap(JsonNode response) {
        String text = response.path("candidates").path(0).path("content").path("parts").path(0)
                .path("text").asText("");
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException("Gemini 응답 파싱 실패: " + text, e);
        }
    }
}
