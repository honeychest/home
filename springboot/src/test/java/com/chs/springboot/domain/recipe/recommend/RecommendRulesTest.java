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

    /** 사전 시드(V11 + V12) 그대로 — 판정을 고정한다. 두 집합은 겹치지 않는다.
        SEASONING = 없을 수 있어 사러 가야 하는 양념 → 부족하면 "양념만 부족" 섹션으로 살아남음. */
    private static final Set<String> SEASONINGS = Set.of(
            "고추장", "된장", "다진생강", "맛술", "물엿", "올리고당", "전분가루");

    /** BASIC = 집에 늘 있는 상비 양념 (V12) → 부족분에서 아예 뺀다. V11 이 양념으로 시드했던 것 중
        상비인 것들이 V12 로 여기 옮겨왔다. 구 ALIASES 표기("다진 마늘"·"백식초")도 함께. */
    private static final Set<String> BASICS = Set.of(
            "물", "찬물", "얼음", "소금", "맛소금", "설탕", "간장", "식용유", "기름",
            "후추", "후춧가루", "참기름", "들기름", "깨", "통깨", "깨소금",
            "다진마늘", "다진 마늘", "고춧가루", "고추가루", "식초", "백식초");

    private static RecommendRules.Candidate candidate(String id, List<String> ingredients) {
        return new RecommendRules.Candidate(
                id, "https://youtu.be/" + id, id + "-title", "http://t/" + id, ingredients, 10, List.of("끓인다"));
    }

    @Test
    @DisplayName("재료·양념 다 있으면 완전 가능")
    void completeWhenNothingMissing() {
        var c = candidate("a", List.of("두부", "고추장"));
        var match = RecommendRules.match(c, Set.of("두부", "고추장"), SEASONINGS, BASICS);

        assertTrue(match.isComplete());
        assertTrue(match.missingMain().isEmpty());
        assertTrue(match.missingSeasoning().isEmpty());
    }

    @Test
    @DisplayName("기본양념(물·소금)은 냉장고에 없어도 부족분에 안 센다 — 이게 없으면 '완전 가능' 섹션이 "
            + "구조적으로 항상 0이다 (2026-07-17 실측: 레시피 115개 중 48개가 '물'을 재료로 적는데 "
            + "냉장고에 물을 넣는 사람은 없어 완전 가능이 0이었음)")
    void basicSeasoningsAreNeverMissing() {
        var c = candidate("a", List.of("두부", "물", "소금", "설탕"));
        var match = RecommendRules.match(c, Set.of("두부"), SEASONINGS, BASICS);

        assertTrue(match.isComplete());
        assertTrue(match.missingSeasoning().isEmpty());
        assertTrue(match.missingMain().isEmpty());
    }

    @Test
    @DisplayName("BASIC 이 SEASONING 을 이긴다 — 두 집합에 같은 이름이 들어가는 사고가 나도 "
            + "'늘 있다'가 우선이라 부족분에 안 샌다")
    void basicWinsOverSeasoning() {
        assertEquals(RecommendRules.Tier.BASIC,
                RecommendRules.classify("소금", Set.of("소금"), Set.of("소금")));
    }

    @Test
    @DisplayName("재료는 다 있고 양념만 없으면 양념만 부족 — 재료 부족 판정에 안 걸림")
    void seasoningOnlyWhenMainPresent() {
        var c = candidate("a", List.of("두부", "고추장"));
        var match = RecommendRules.match(c, Set.of("두부"), SEASONINGS, BASICS);

        assertTrue(match.isSeasoningOnly());
        assertEquals(List.of("고추장"), match.missingSeasoning());
        assertTrue(match.missingMain().isEmpty());
    }

    @Test
    @DisplayName("재료가 몇 개든 없으면 재료 부족 — 부족 개수를 그대로 담는다")
    void missingMainCarriesCount() {
        var c = candidate("a", List.of("두부", "대파", "고추장"));
        var match = RecommendRules.match(c, Set.of(), SEASONINGS, BASICS);

        assertTrue(match.isMissingMain());
        assertEquals(List.of("두부", "대파"), match.missingMain());
        assertEquals(List.of("고추장"), match.missingSeasoning());
    }

    @Test
    @DisplayName("재료가 4개 이상 없어도 재료 부족 섹션에 걸린다 (2026-07-14 확정 — 상한으로 제외하면 " +
            "완전 매칭이 하나도 없을 때 추천이 통째로 안 보이는 문제가 실사용에서 발견됨)")
    void manyMissingStillCountsAsMissingMain() {
        var c = candidate("a", List.of("두부", "대파", "돼지고기", "김치"));
        var match = RecommendRules.match(c, Set.of(), SEASONINGS, BASICS);

        assertTrue(match.isMissingMain());
        assertEquals(4, match.missingMain().size());
    }

    @Test
    @DisplayName("사전에 있는 이름만 양념 — 사전에 없는 이름은 판정 전이라 MAIN(안전 기본값). "
            + "'굴소스'가 그 사례: 사전 도입 전엔 코드 상수 19개 밖이라 주재료로 잡혀 추천이 밀렸다")
    void unknownNamesAreMain() {
        var c = candidate("a", List.of("고추장", "다진 마늘", "굴소스"));
        var match = RecommendRules.match(c, Set.of(), SEASONINGS, BASICS);

        assertEquals(List.of("고추장"), match.missingSeasoning()); // 사전의 SEASONING
        assertEquals(List.of("굴소스"), match.missingMain());       // 사전에 없음 → MAIN
        // "다진 마늘"(BASIC)은 어느 쪽에도 안 들어간다 — 늘 있다고 간주
    }

    @Test
    @DisplayName("섹션 분류·정렬: 재료 부족은 적은 개수 순(상한 없이 멀리 부족한 것도 뒤에 포함), " +
            "각 섹션 최대 5개로 자른다")
    void bucketsSortsAndCaps() {
        var complete = candidate("complete", List.of("두부"));
        var seasoningOnly = candidate("seasoning", List.of("두부", "고추장"));
        var missingFar = candidate("missing-far", List.of("두부", "대파", "돼지고기", "김치"));
        var missingTwo = candidate("missing-two", List.of("두부", "대파", "김치"));
        var missingOne = candidate("missing-one", List.of("두부", "대파"));

        var matches = List.of(
                RecommendRules.match(complete, Set.of("두부"), SEASONINGS, BASICS),
                RecommendRules.match(seasoningOnly, Set.of("두부"), SEASONINGS, BASICS),
                RecommendRules.match(missingFar, Set.of("두부"), SEASONINGS, BASICS),
                RecommendRules.match(missingTwo, Set.of("두부"), SEASONINGS, BASICS),
                RecommendRules.match(missingOne, Set.of("두부"), SEASONINGS, BASICS));

        var sections = RecommendRules.bucket(matches);

        assertEquals(1, sections.complete().size());
        assertEquals("complete", sections.complete().get(0).candidate().videoId());
        assertEquals(1, sections.seasoningOnly().size());
        assertEquals("seasoning", sections.seasoningOnly().get(0).candidate().videoId());
        assertEquals(List.of("missing-one", "missing-two", "missing-far"),
                sections.needsIngredients().stream().map(m -> m.candidate().videoId()).toList());
    }

    @Test
    @DisplayName("상세 팝업용 재료 목록 — 원문 순서 유지, 있음/없음만 표시. 기본양념(소금)은 "
            + "냉장고에 없어도 '있음' — 부족분이 아니니 상세에서도 갖춘 것으로 보여야 일관된다")
    void ingredientStatusesKeepOrderAndHaveFlag() {
        var c = candidate("a", List.of("두부", "대파", "소금", "고추장"));
        var match = RecommendRules.match(c, Set.of("두부"), SEASONINGS, BASICS);

        assertEquals(
                List.of(
                        new RecommendRules.IngredientStatus("두부", true),
                        new RecommendRules.IngredientStatus("대파", false),
                        new RecommendRules.IngredientStatus("소금", true),
                        new RecommendRules.IngredientStatus("고추장", false)),
                match.ingredientStatuses());
    }

    @Test
    @DisplayName("각 섹션은 최대 5개 — 6개째부터는 잘린다")
    void capsAtFivePerSection() {
        var matches = java.util.stream.IntStream.range(0, 7)
                .mapToObj(i -> RecommendRules.match(candidate("c" + i, List.of("두부")), Set.of("두부"), SEASONINGS, BASICS))
                .toList();

        var sections = RecommendRules.bucket(matches);

        assertEquals(5, sections.complete().size());
    }
}
