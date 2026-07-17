// [AGENT] 재료 사전 AI 일괄 점검 (2026-07-17 5차-4 슬라이스1) — 온디맨드. 재료 이름 목록만 보고
// 각각이 양념(SEASONING)인지 주재료(MAIN)인지 판정을 "제안"한다(자동 적용 아님 — 오너가 monitor
// 에서 확인 후 반영). 재료 이름 목록만 보는 폐쇄적 어휘 판단이라 LLM 이 안정적으로 잘하는 일
// (영상 세계지식 추론과 성격이 다름). 그래도 안전 비대칭 원칙상 제안까지만.
// Gemini 호출 봉투·일시적 실패 매핑은 GeminiJsonClient seam 이 소유 — 여기는 프롬프트·스키마·
// 알맹이 파싱만 (2026-07-17 아키텍처 점검에서 GeminiRecipeExtractor 와 공유 seam 으로 통합).
package com.chs.springboot.domain.recipe.registration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

@Component
public class IngredientAuditor {

    private static final String PROMPT = """
            아래는 요리 레시피에서 추출된 재료 이름 목록입니다.
            각 이름이 '양념·조미료(SEASONING)'인지 '주재료(MAIN)'인지 판정하세요.
            - SEASONING: 소금·간장·설탕·고춧가루·참기름·다진마늘 같은 조미료/양념류.
            - MAIN: 그 외 실제 식재료(고기·채소·두부·면·떡 등).
            확실하지 않으면 MAIN 으로 두세요(안전 기본값). 주어진 이름만 판정하고, 새 이름을
            만들거나 표기를 바꾸지 마세요. 결과는 각 이름에 대해 {name, tier} 로만.
            """;

    private final GeminiJsonClient gemini;
    private final GikkaMediaProperties properties;

    public IngredientAuditor(GeminiJsonClient gemini, GikkaMediaProperties properties) {
        this.gemini = gemini;
        this.properties = properties;
    }

    /** 오너가 확인할 제안 한 건 — 이 이름에 대해 LLM 이 제시한 tier(MAIN/SEASONING). 자동 반영 아님. */
    public record Proposal(String name, String suggestedTier) {
    }

    /** names 를 판정해 제안 목록을 돌려준다. 키가 없거나 이름이 없으면 빈 목록. */
    public List<Proposal> audit(List<String> names) {
        if (names.isEmpty() || properties.getGeminiApiKey().isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> parts = List.of(
                Map.of("text", PROMPT),
                Map.of("text", "재료 이름 목록:\n" + String.join("\n", names)));
        Map<String, Object> generationConfig = Map.of(
                "responseMimeType", "application/json",
                "responseSchema", responseSchema());
        return parse(gemini.generate(properties.getGeminiModel(), parts, generationConfig));
    }

    private static Map<String, Object> responseSchema() {
        return Map.of(
                "type", "ARRAY",
                "items", Map.of(
                        "type", "OBJECT",
                        "properties", Map.of(
                                "name", Map.of("type", "STRING"),
                                "tier", Map.of("type", "STRING", "enum", List.of("MAIN", "SEASONING"))),
                        "required", List.of("name", "tier")));
    }

    /** 알맹이 JSON(제안 배열)을 제안 목록으로 — 순수(HTTP·봉투 없음, GeminiJsonClient 이 이미 벗김) */
    static List<Proposal> parse(JsonNode array) {
        List<Proposal> proposals = new ArrayList<>();
        for (JsonNode node : array) {
            String name = node.path("name").asText("").trim();
            String tier = node.path("tier").asText("MAIN");
            if (!name.isEmpty()) {
                proposals.add(new Proposal(name, "SEASONING".equals(tier) ? "SEASONING" : "MAIN"));
            }
        }
        return proposals;
    }
}
