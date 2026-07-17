// [AGENT] 재료 사전 AI 일괄 점검 (2026-07-17 5차-4 슬라이스1) — 온디맨드. 재료 이름 목록만 보고
// 각각이 양념(SEASONING)인지 주재료(MAIN)인지 판정을 "제안"한다(자동 적용 아님 — 오너가 monitor
// 에서 확인 후 반영). 재료 이름 목록만 보는 폐쇄적 어휘 판단이라 LLM 이 안정적으로 잘하는 일
// (영상 세계지식 추론과 성격이 다름). 그래도 안전 비대칭 원칙상 제안까지만.
// Gemini 호출 봉투·일시적 실패 매핑은 GeminiJsonClient seam 이 소유 — 여기는 프롬프트·스키마·
// 알맹이 파싱만 (2026-07-17 아키텍처 점검에서 GeminiRecipeExtractor 와 공유 seam 으로 통합).
//
// 판정 대상 = PENDING 이름만, 전체 대표 목록 = mergeInto 후보를 찾기 위한 참고 자료(2026-07-18
// 확정). 예전엔 대표 전체(사전 커질수록 커짐, 실측 243개)를 판정 대상으로 보내 응답 크기가
// 사전 크기에 비례해 자라다가 gikka-local max_tokens 를 넘겨 503 이 재발했다. 이제 응답 크기는
// 신규(PENDING) 개수에만 비례한다. 트레이드오프: 이미 CONFIRMED 인 대표끼리 뒤늦게 묶자는 제안은
// 더 이상 안 나온다(그 경우 어차피 "매칭이 덜 될 뿐"인 안전한 실패 모드 — CONTEXT.md 참고).
package com.chs.springboot.domain.recipe.registration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

@Component
public class IngredientAuditor {

    private static final String PROMPT = """
            아래 "판정 대상" 목록의 각 이름에 대해서만 두 가지를 판정하세요.
            "참고용 전체 대표 목록"은 판정하지 마세요 — mergeInto 값을 고를 때만 참고하는 자료입니다.

            [1] tier — BASIC / SEASONING / MAIN 중 하나.
            - BASIC: 어느 집에나 늘 있는 상비 양념. 물·소금·설탕·간장·식용유·후추·참기름·통깨·
              다진마늘·고춧가루·식초 같은 것. "장 보러 갈 필요가 없는" 것만.
            - SEASONING: 양념·조미료지만 없을 수 있어 사러 가야 하는 것.
              고추장·굴소스·두반장·액젓·물엿·마요네즈 같은 것.
            - MAIN: 그 외 실제 식재료(고기·채소·두부·면·떡 등).
            확실하지 않으면 MAIN 으로 두세요(안전 기본값 — 양념으로 잘못 빼면 레시피가 추천에서
            사라지지만, 주재료로 두면 "부족 재료"로 보일 뿐이라 덜 위험합니다).

            [2] mergeInto — 이 이름을 흡수할 대표 이름. 묶을 필요가 없으면 빈 문자열.
            같은 것이 여러 이름으로 흩어져 있으면 대표 하나로 묶습니다. 냉장고에 대표가 있으면
            멤버도 있는 것으로 칩니다.
            - 대표 이름은 반드시 "참고용 전체 대표 목록" 안에 있는 이름이어야 합니다(판정 대상
              자기 자신은 제외 — 자기 자신으로 묶는 건 의미가 없습니다). 새 이름을 만들지 마세요.
            - 묶는 예: 수량·괄호 표기만 다른 것("계란 2개" → "계란"), 구성품("라면 건더기스프" →
              "라면", 후레이크·스프도 같음), 상표·세부 변형이라 서로 대체되는 것("신라면" → "라면",
              "밀떡" → "떡", "대파" → "파").
            - 절대 묶으면 안 되는 예: 실제로 다른 재료. "진간장"과 "간장", "맛소금"과 "소금",
              "파프리카"와 "파"는 각각 다른 것이라 묶지 마세요.
            - 판단 기준: "대표가 냉장고에 있으면 이 재료로 요리할 수 있는가?" 가 확실히 참일 때만
              묶으세요. 조금이라도 애매하면 빈 문자열로 두세요 — 안 묶으면 매칭이 덜 될 뿐이지만,
              잘못 묶으면 없는 재료를 있다고 말하게 됩니다.

            판정 대상만 판정하고 표기를 바꾸지 마세요. 결과는 판정 대상 각 이름에 대해
            {name, tier, mergeInto}.
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

    /**
     * 오너가 확인할 제안 한 건 — 자동 반영 아님(안전 비대칭 원칙).
     *
     * @param suggestedTier 분류 제안. 묶기 제안이면 null — 묶이는 순간 양념 여부는 대표가 정하므로
     *                      멤버의 tier 를 따로 제안할 이유가 없다.
     * @param mergeInto     묶기 제안(이 이름을 흡수할 대표). 없으면 null.
     */
    public record Proposal(String name, String suggestedTier, String mergeInto) {

        boolean isMerge() {
            return mergeInto != null;
        }
    }

    /**
     * pendingNames 만 판정해 제안 목록을 돌려준다 — allRepresentatives 는 mergeInto 후보를
     * 찾기 위한 참고 자료일 뿐 판정 대상이 아니다(응답 크기가 사전 전체가 아니라 신규 개수에
     * 비례하게 만드는 핵심, 2026-07-18). pendingNames 가 비면 신규가 없다는 뜻이라 LLM 호출
     * 자체를 생략한다(한도 절약).
     */
    public List<Proposal> audit(List<String> pendingNames, List<String> allRepresentatives) {
        if (pendingNames.isEmpty() || properties.getGeminiApiKey().isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> parts = List.of(
                Map.of("text", PROMPT),
                Map.of("text", "판정 대상:\n" + String.join("\n", pendingNames)),
                Map.of("text", "참고용 전체 대표 목록(mergeInto 후보):\n"
                        + String.join("\n", allRepresentatives)));
        // thinkingBudget 0 = 사고 끄기. 재료 이름만 보고 양념/주재료를 가르는 건 폐쇄적 어휘
        // 판단이라 사고가 거의 기여하지 않는다 — 2026-07-17 실측(243개): 사고 ON 24.7초·양념 88개
        // vs OFF 10.9초·양념 86개(차이는 파슬리·육수류 같은 경계 6개뿐, 어차피 오너가 확정하는
        // "제안"이라 무해). 사고 토큰 4,706개도 함께 절약된다(무료 한도).
        Map<String, Object> generationConfig = Map.of(
                "responseMimeType", "application/json",
                "responseSchema", responseSchema(pendingNames),
                "thinkingConfig", Map.of("thinkingBudget", 0));
        return parse(gemini.generate(properties.getGeminiModel(), parts, generationConfig));
    }

    /** name 필드를 pendingNames 로 enum 강제 — 판정 대상 밖 이름을 스키마 차원에서 막는다. */
    private static Map<String, Object> responseSchema(List<String> pendingNames) {
        return Map.of(
                "type", "ARRAY",
                "items", Map.of(
                        "type", "OBJECT",
                        "properties", Map.of(
                                "name", Map.of("type", "STRING", "enum", pendingNames),
                                "tier", Map.of("type", "STRING",
                                        "enum", List.of(TIER_MAIN, TIER_SEASONING, TIER_BASIC)),
                                "mergeInto", Map.of("type", "STRING")),
                        "required", List.of("name", "tier", "mergeInto")));
    }

    /** 알맹이 JSON(제안 배열)을 제안 목록으로 — 순수(HTTP·봉투 없음, GeminiJsonClient 이 이미 벗김).
        모르는 tier 값은 전부 MAIN 으로 정규화한다(안전 기본값 — 스키마가 enum 을 강제하지만
        모델이 어긴 경우에도 위험한 쪽으로 기울지 않게).
        묶기 제안이면 tier 는 버린다 — 멤버의 양념 여부는 대표가 정하므로 둘을 같이 제안하면
        오너가 뭘 승인하는 건지 흐려진다. 자기 자신에게 묶으라는 답(mergeInto == name)은
        "안 묶음"과 같은 뜻이라 그렇게 정규화한다.
        제안이 사전에 실재하는 이름인지·대표가 대표 자격이 있는지는 여기서 안 본다 —
        그건 사전을 아는 호출부(IngredientAuditController)의 몫이다(이 클래스는 사전을 모른다). */
    static List<Proposal> parse(JsonNode array) {
        List<Proposal> proposals = new ArrayList<>();
        for (JsonNode node : array) {
            String name = node.path("name").asText("").trim();
            if (name.isEmpty()) {
                continue;
            }
            String mergeInto = node.path("mergeInto").asText("").trim();
            if (!mergeInto.isEmpty() && !mergeInto.equals(name)) {
                proposals.add(new Proposal(name, null, mergeInto));
                continue;
            }
            String tier = node.path("tier").asText(TIER_MAIN);
            boolean known = TIER_SEASONING.equals(tier) || TIER_BASIC.equals(tier);
            proposals.add(new Proposal(name, known ? tier : TIER_MAIN, null));
        }
        return proposals;
    }
}
