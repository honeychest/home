// [AGENT] Gemini 구현체 — 유튜브 URL 직접 전달 (영상 다운로드 없음, CONTEXT.md "추출" 확정)
// 분류(RECIPE/TIP/ETC)와 추출을 1회 호출에 합침 (2026-07-12 확정 — 한도 절약).
// media_resolution 저해상도로 시작 — 품질 부족 시에만 올린다 (2026-07-11 조사).
// 외부 HTTP 는 RestClient.Builder 주입 (PLAYBOOK 관례 4).
package com.chs.springboot.domain.recipe.registration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Component
public class GeminiRecipeExtractor implements RecipeExtractor {

    private static final String PROMPT = """
            이 영상을 보고 판단해 주세요.
            1. category: 요리 레시피 영상이면 RECIPE, 생활팁·요령 영상이면 TIP, 둘 다 아니면 ETC.
            2. RECIPE 인 경우에만:
               - name: 요리 이름 (짧게)
               - ingredients: 재료 목록. 영상에 나온 이름 그대로 쓰고, 임의로 바꾸지 마세요.
                 양념(소금, 간장 등)도 포함. 수량·단위는 빼고 이름만.
               - cookMinutes: 예상 조리 시간(분). 영상에서 알 수 없으면 생략.
               - steps: 조리 순서 요약. 각 단계를 짧은 한 문장으로, 3~7개.
            3. RECIPE 가 아닌 경우에만:
               - summary: 영상의 요점 요약 2~3문장. 나중에 다시 찾을 때 내용을 떠올릴 수 있게.
            4. tags: 모든 영상 공통. 이 영상을 검색할 때 쓸 만한 키워드 3~8개
               (예: 신발 끈 묶는 영상이면 ["신발끈", "매듭", "운동화"]). 짧은 명사 위주로.
            모든 텍스트는 한국어로.
            """;

    private static final Logger log = LoggerFactory.getLogger(GeminiRecipeExtractor.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient rest;
    private final GikkaMediaProperties properties;
    private final GikkaTelegramNotifier notifier;

    // 모델 폐쇄 페일오버 (2026-07-12 승인): 설정 모델이 404 를 내면 폴백 모델로 전환하고
    // 재기동 전까지 유지한다 (매 호출 404 낭비 방지. 인스턴스별 판정 — 2인스턴스 각자 전환).
    private volatile boolean failedOver = false;

    public GeminiRecipeExtractor(RestClient.Builder builder, GikkaMediaProperties properties,
                                 GikkaTelegramNotifier notifier) {
        this.rest = builder.baseUrl("https://generativelanguage.googleapis.com").build();
        this.properties = properties;
        this.notifier = notifier;
    }

    @Override
    public ExtractionResult extract(String videoUrl) {
        String model = failedOver ? properties.getGeminiFallbackModel() : properties.getGeminiModel();
        try {
            return callGemini(model, videoUrl);
        } catch (HttpClientErrorException.NotFound e) {
            if (failedOver) {
                throw e; // 폴백 모델까지 404 — 일반 실패 경로(3회 후 FAILED)로
            }
            failedOver = true;
            String fallback = properties.getGeminiFallbackModel();
            log.warn("[gikka] Gemini 모델 {} 404 — {} 로 페일오버", model, fallback);
            notifier.notify("[기까] Gemini 모델 '" + model + "' 이 404(폐쇄 추정)입니다. '"
                    + fallback + "' 로 자동 전환해 분석을 계속합니다. 설정 모델 교체가 필요합니다.");
            return callGemini(fallback, videoUrl);
        }
    }

    private ExtractionResult callGemini(String model, String videoUrl) {
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(
                        Map.of("fileData", Map.of("fileUri", videoUrl)),
                        Map.of("text", PROMPT)))),
                "generationConfig", Map.of(
                        "responseMimeType", "application/json",
                        "responseSchema", responseSchema(),
                        "mediaResolution", "MEDIA_RESOLUTION_LOW"));
        try {
            JsonNode response = rest.post()
                    .uri("/v1beta/models/{model}:generateContent?key={key}",
                            model, properties.getGeminiApiKey())
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            return parseEnvelope(response);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                throw new RateLimitedException(e.getMessage());
            }
            throw e;
        }
    }

    /** 구조화 출력 스키마 — 모델이 자유 서술로 새는 것을 방지 */
    private static Map<String, Object> responseSchema() {
        return Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "category", Map.of("type", "STRING", "enum", List.of("RECIPE", "TIP", "ETC")),
                        "name", Map.of("type", "STRING"),
                        "ingredients", Map.of("type", "ARRAY", "items", Map.of("type", "STRING")),
                        "cookMinutes", Map.of("type", "INTEGER"),
                        "steps", Map.of("type", "ARRAY", "items", Map.of("type", "STRING")),
                        "summary", Map.of("type", "STRING"),
                        "tags", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))),
                "required", List.of("category"));
    }

    /** Gemini 응답 봉투 파싱 — HTTP 없이 검증하는 순수 판정 (테스트: GeminiRecipeExtractorTest) */
    static ExtractionResult parseEnvelope(JsonNode response) {
        String text = response.path("candidates").path(0).path("content").path("parts").path(0)
                .path("text").asText("");
        try {
            JsonNode json = MAPPER.readTree(text);
            String category = json.path("category").asText("ETC");
            List<String> tags = toList(json.path("tags")); // 검색 태그는 전 분류 공통 (2026-07-13 확정)
            if (!"RECIPE".equals(category)) {
                return new ExtractionResult(category, null, null, null, null,
                        json.path("summary").asText(null), tags);
            }
            return new ExtractionResult(
                    category,
                    json.path("name").asText(null),
                    toList(json.path("ingredients")),
                    json.hasNonNull("cookMinutes") ? json.get("cookMinutes").asInt() : null,
                    toList(json.path("steps")),
                    null, // RECIPE 요약은 name·steps 가 대신함
                    tags);
        } catch (Exception e) {
            throw new IllegalStateException("Gemini 응답 파싱 실패: " + text, e);
        }
    }

    private static List<String> toList(JsonNode array) {
        List<String> list = new ArrayList<>();
        for (JsonNode node : array) {
            if (!node.asText().isBlank()) {
                list.add(node.asText().trim());
            }
        }
        return list;
    }
}
