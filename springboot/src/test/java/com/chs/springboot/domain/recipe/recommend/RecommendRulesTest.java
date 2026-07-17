// [AGENT] 추천 3단계 판정 고정 (DB·HTTP 없는 순수 테스트) — CONTEXT.md 4차 확정(2026-07-14 grill-me)
// 2026-07-17 5차-4 슬라이스1: classify/match 가 양념 이름 집합을 인자로 받도록 바뀜(사전 DB화).
// 순수 모듈 규율 유지 — 테스트는 사전 대신 SEASONINGS 집합을 직접 주입해 판정을 고정한다.
package com.chs.springboot.domain.recipe.recommend;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecommendRulesTest {

    /** 사전(V11)이 CONFIRMED_SEASONING 으로 시드하는 양념 이름 집합과 동일하게 — 판정을 고정한다.
        구 ALIASES 는 양쪽 표기를 모두 양념으로 시드해 흡수됨("다진 마늘"·"백식초" 포함). */
    private static final Set<String> SEASONINGS = Set.of(
            "물", "소금", "설탕", "간장", "고추장", "고춧가루", "된장", "후추", "식용유",
            "참기름", "들기름", "다진마늘", "다진생강", "맛술", "식초", "물엿", "올리고당",
            "전분가루", "통깨", "다진 마늘", "백식초");

    private static RecommendRules.Candidate candidate(String id, List<String> ingredients) {
        return new RecommendRules.Candidate(
                id, "https://youtu.be/" + id, id + "-title", "http://t/" + id, ingredients, 10, List.of("끓인다"));
    }

    @Test
    @DisplayName("재료·양념 다 있으면 완전 가능")
    void completeWhenNothingMissing() {
        var c = candidate("a", List.of("두부", "소금"));
        var match = RecommendRules.match(c, Set.of("두부", "소금"), SEASONINGS);

        assertTrue(match.isComplete());
        assertTrue(match.missingMain().isEmpty());
        assertTrue(match.missingSeasoning().isEmpty());
    }

    @Test
    @DisplayName("재료는 다 있고 양념만 없으면 양념만 부족 — 재료 부족 판정에 안 걸림")
    void seasoningOnlyWhenMainPresent() {
        var c = candidate("a", List.of("두부", "간장"));
        var match = RecommendRules.match(c, Set.of("두부"), SEASONINGS);

        assertTrue(match.isSeasoningOnly());
        assertEquals(List.of("간장"), match.missingSeasoning());
        assertTrue(match.missingMain().isEmpty());
    }

    @Test
    @DisplayName("재료가 몇 개든 없으면 재료 부족 — 부족 개수를 그대로 담는다")
    void missingMainCarriesCount() {
        var c = candidate("a", List.of("두부", "대파", "고춧가루"));
        var match = RecommendRules.match(c, Set.of(), SEASONINGS);

        assertTrue(match.isMissingMain());
        assertEquals(List.of("두부", "대파"), match.missingMain());
        assertEquals(List.of("고춧가루"), match.missingSeasoning());
    }

    @Test
    @DisplayName("재료가 4개 이상 없어도 재료 부족 섹션에 걸린다 (2026-07-14 확정 — 상한으로 제외하면 " +
            "완전 매칭이 하나도 없을 때 추천이 통째로 안 보이는 문제가 실사용에서 발견됨)")
    void manyMissingStillCountsAsMissingMain() {
        var c = candidate("a", List.of("두부", "대파", "돼지고기", "김치"));
        var match = RecommendRules.match(c, Set.of(), SEASONINGS);

        assertTrue(match.isMissingMain());
        assertEquals(4, match.missingMain().size());
    }

    @Test
    @DisplayName("사전이 양념으로 시드한 이름만 SEASONING — '다진 마늘'·'백식초'는 양념, 종류가 다른 " +
            "'맛소금'은 사전에 없어 MAIN (2026-07-17 5차-4: 코드 상수→사전 이관, 판정 규칙은 동일)")
    void onlyDictionarySeasoningsAreSeasoning() {
        var c = candidate("a", List.of("다진 마늘", "백식초", "맛소금"));
        var match = RecommendRules.match(c, Set.of(), SEASONINGS);

        assertEquals(List.of("다진 마늘", "백식초"), match.missingSeasoning());
        assertEquals(List.of("맛소금"), match.missingMain());
    }

    @Test
    @DisplayName("섹션 분류·정렬: 재료 부족은 적은 개수 순(상한 없이 멀리 부족한 것도 뒤에 포함), " +
            "각 섹션 최대 5개로 자른다")
    void bucketsSortsAndCaps() {
        var complete = candidate("complete", List.of("두부"));
        var seasoningOnly = candidate("seasoning", List.of("두부", "간장"));
        var missingFar = candidate("missing-far", List.of("두부", "대파", "돼지고기", "김치"));
        var missingTwo = candidate("missing-two", List.of("두부", "대파", "김치"));
        var missingOne = candidate("missing-one", List.of("두부", "대파"));

        var matches = List.of(
                RecommendRules.match(complete, Set.of("두부"), SEASONINGS),
                RecommendRules.match(seasoningOnly, Set.of("두부"), SEASONINGS),
                RecommendRules.match(missingFar, Set.of("두부"), SEASONINGS),
                RecommendRules.match(missingTwo, Set.of("두부"), SEASONINGS),
                RecommendRules.match(missingOne, Set.of("두부"), SEASONINGS));

        var sections = RecommendRules.bucket(matches);

        assertEquals(1, sections.complete().size());
        assertEquals("complete", sections.complete().get(0).candidate().videoId());
        assertEquals(1, sections.seasoningOnly().size());
        assertEquals("seasoning", sections.seasoningOnly().get(0).candidate().videoId());
        assertEquals(List.of("missing-one", "missing-two", "missing-far"),
                sections.needsIngredients().stream().map(m -> m.candidate().videoId()).toList());
    }

    @Test
    @DisplayName("상세 팝업용 재료 목록 — 원문 순서 유지, 있음/없음만 표시")
    void ingredientStatusesKeepOrderAndHaveFlag() {
        var c = candidate("a", List.of("두부", "대파", "소금"));
        var match = RecommendRules.match(c, Set.of("두부"), SEASONINGS);

        assertEquals(
                List.of(
                        new RecommendRules.IngredientStatus("두부", true),
                        new RecommendRules.IngredientStatus("대파", false),
                        new RecommendRules.IngredientStatus("소금", false)),
                match.ingredientStatuses());
    }

    @Test
    @DisplayName("각 섹션은 최대 5개 — 6개째부터는 잘린다")
    void capsAtFivePerSection() {
        var matches = java.util.stream.IntStream.range(0, 7)
                .mapToObj(i -> RecommendRules.match(candidate("c" + i, List.of("두부")), Set.of("두부"), SEASONINGS))
                .toList();

        var sections = RecommendRules.bucket(matches);

        assertEquals(5, sections.complete().size());
    }
}
