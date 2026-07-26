// [AGENT] 재료 사전 판정의 Gemini 어댑터 (2026-07-17 5차-4 슬라이스1). 재료 이름 목록만 보고
// 각각이 양념(SEASONING)인지 주재료(MAIN)인지 판정을 "제안"한다(자동 적용 아님 — 안전 비대칭 원칙).
// 재료 이름 목록만 보는 폐쇄적 어휘 판단이라 LLM 이 안정적으로 잘하는 일(영상 세계지식 추론과 성격이 다름).
// Gemini 호출 봉투·일시적 실패 매핑은 GeminiJsonClient seam 이 소유 — 여기는 프롬프트·스키마뿐이다
// (2026-07-17 아키텍처 점검에서 GeminiRecipeExtractor 와 공유 seam 으로 통합).
//
// 이 클래스는 채널 하나일 뿐이다 (2026-07-25 점검) — 판정 대상 선정·라우팅·제안 검증은
// DictionaryJudge, 제안 어휘와 응답 파싱은 IngredientJudge 시임이 소유한다. 예전엔 이 셋이 전부
// 여기와 두 호출부에 흩어져 있었고, 로컬 어댑터가 이 클래스의 parse 를 들여다보고 있었다.
//
// 2026-07-26 registration → dictionary 이관 (공유 seam 은 external 중립 지대로).
package com.chs.springboot.domain.recipe.dictionary;

import java.util.List;
import java.util.Map;

import com.chs.springboot.domain.recipe.external.GeminiJsonClient;
import com.chs.springboot.domain.recipe.external.GikkaLlmProperties;

import org.springframework.stereotype.Component;

@Component
public class IngredientAuditor implements IngredientJudge {

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
    private final GikkaLlmProperties properties;

    public IngredientAuditor(GeminiJsonClient gemini, GikkaLlmProperties properties) {
        this.gemini = gemini;
        this.properties = properties;
    }

    /** 계약(판정 대상 · 참고 목록의 뜻)은 IngredientJudge 가 소유한다. 여기선 키가 없으면 조용히
        빈 목록 — 키 없는 환경(로컬 개발 기본)에서 [AI 점검]이 500 으로 죽지 않게. */
    @Override
    public List<Proposal> audit(List<String> pendingNames, List<String> allRepresentatives) {
        if (pendingNames.isEmpty() || properties.getApiKey().isBlank()) {
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
        return IngredientJudge.parse(gemini.generate(properties.getModel(), parts, generationConfig));
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
}
