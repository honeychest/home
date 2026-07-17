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
            각 이름을 BASIC / SEASONING / MAIN 중 하나로 판정하세요.
            - BASIC: 어느 집에나 늘 있는 상비 양념. 물·소금·설탕·간장·식용유·후추·참기름·통깨·
              다진마늘·고춧가루·식초 같은 것. "장 보러 갈 필요가 없는" 것만.
            - SEASONING: 양념·조미료지만 없을 수 있어 사러 가야 하는 것.
              고추장·굴소스·두반장·액젓·물엿·마요네즈 같은 것.
            - MAIN: 그 외 실제 식재료(고기·채소·두부·면·떡 등).
            확실하지 않으면 MAIN 으로 두세요(안전 기본값 — 양념으로 잘못 빼면 레시피가 추천에서
            사라지지만, 주재료로 두면 "부족 재료"로 보일 뿐이라 덜 위험합니다).
            주어진 이름만 판정하고, 새 이름을 만들거나 표기를 바꾸지 마세요.
            결과는 각 이름에 대해 {name, tier} 로만.
            """;

    private final GeminiJsonClient gemini;
    private final GikkaMediaProperties properties;

    public IngredientAuditor(GeminiJsonClient gemini, GikkaMediaProperties properties) {
        this.gemini = gemini;
        this.properties = properties;
    }

    /** LLM 이 제시한 분류 — 안전 기본값이자 "제안할 게 없음"을 뜻하는 MAIN 포함 */
    public static final String TIER_MAIN = "MAIN";
    public static final String TIER_SEASONING = "SEASONING";
    public static final String TIER_BASIC = "BASIC";

    /** 오너가 확인할 제안 한 건 — 이 이름에 대해 LLM 이 제시한 tier. 자동 반영 아님. */
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
        // thinkingBudget 0 = 사고 끄기. 재료 이름만 보고 양념/주재료를 가르는 건 폐쇄적 어휘
        // 판단이라 사고가 거의 기여하지 않는다 — 2026-07-17 실측(243개): 사고 ON 24.7초·양념 88개
        // vs OFF 10.9초·양념 86개(차이는 파슬리·육수류 같은 경계 6개뿐, 어차피 오너가 확정하는
        // "제안"이라 무해). 사고 토큰 4,706개도 함께 절약된다(무료 한도).
        Map<String, Object> generationConfig = Map.of(
                "responseMimeType", "application/json",
                "responseSchema", responseSchema(),
                "thinkingConfig", Map.of("thinkingBudget", 0));
        return parse(gemini.generate(properties.getGeminiModel(), parts, generationConfig));
    }

    private static Map<String, Object> responseSchema() {
        return Map.of(
                "type", "ARRAY",
                "items", Map.of(
                        "type", "OBJECT",
                        "properties", Map.of(
                                "name", Map.of("type", "STRING"),
                                "tier", Map.of("type", "STRING",
                                        "enum", List.of(TIER_MAIN, TIER_SEASONING, TIER_BASIC))),
                        "required", List.of("name", "tier")));
    }

    /** 알맹이 JSON(제안 배열)을 제안 목록으로 — 순수(HTTP·봉투 없음, GeminiJsonClient 이 이미 벗김).
        모르는 tier 값은 전부 MAIN 으로 정규화한다(안전 기본값 — 스키마가 enum 을 강제하지만
        모델이 어긴 경우에도 위험한 쪽으로 기울지 않게). */
    static List<Proposal> parse(JsonNode array) {
        List<Proposal> proposals = new ArrayList<>();
        for (JsonNode node : array) {
            String name = node.path("name").asText("").trim();
            String tier = node.path("tier").asText(TIER_MAIN);
            if (!name.isEmpty()) {
                boolean known = TIER_SEASONING.equals(tier) || TIER_BASIC.equals(tier);
                proposals.add(new Proposal(name, known ? tier : TIER_MAIN));
            }
        }
        return proposals;
    }
}
