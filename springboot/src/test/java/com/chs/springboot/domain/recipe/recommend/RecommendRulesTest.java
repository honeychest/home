// [AGENT] 추천 3단계 판정 고정 (DB·HTTP 없는 순수 테스트) — CONTEXT.md 4차 확정(2026-07-14 grill-me)
package com.chs.springboot.domain.recipe.recommend;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecommendRulesTest {

    private static RecommendRules.Candidate candidate(String id, List<String> ingredients) {
        return new RecommendRules.Candidate(
                id, "https://youtu.be/" + id, id + "-title", "http://t/" + id, ingredients, 10, List.of("끓인다"));
    }

    @Test
    @DisplayName("재료·양념 다 있으면 완전 가능")
    void completeWhenNothingMissing() {
        var c = candidate("a", List.of("두부", "소금"));
        var match = RecommendRules.match(c, Set.of("두부", "소금"));

        assertTrue(match.isComplete());
        assertTrue(match.missingMain().isEmpty());
        assertTrue(match.missingSeasoning().isEmpty());
    }

    @Test
    @DisplayName("재료는 다 있고 양념만 없으면 양념만 부족 — 재료 부족 판정에 안 걸림")
    void seasoningOnlyWhenMainPresent() {
        var c = candidate("a", List.of("두부", "간장"));
        var match = RecommendRules.match(c, Set.of("두부"));

        assertTrue(match.isSeasoningOnly());
        assertEquals(List.of("간장"), match.missingSeasoning());
        assertTrue(match.missingMain().isEmpty());
    }

    @Test
    @DisplayName("재료가 몇 개든 없으면 재료 부족 — 부족 개수를 그대로 담는다")
    void missingMainCarriesCount() {
        var c = candidate("a", List.of("두부", "대파", "고춧가루"));
        var match = RecommendRules.match(c, Set.of());

        assertTrue(match.isMissingMain());
        assertEquals(List.of("두부", "대파"), match.missingMain());
        assertEquals(List.of("고춧가루"), match.missingSeasoning());
    }

    @Test
    @DisplayName("재료가 4개 이상 없어도 재료 부족 섹션에 걸린다 (2026-07-14 확정 — 상한으로 제외하면 " +
            "완전 매칭이 하나도 없을 때 추천이 통째로 안 보이는 문제가 실사용에서 발견됨)")
    void manyMissingStillCountsAsMissingMain() {
        var c = candidate("a", List.of("두부", "대파", "돼지고기", "김치"));
        var match = RecommendRules.match(c, Set.of());

        assertTrue(match.isMissingMain());
        assertEquals(4, match.missingMain().size());
    }

    @Test
    @DisplayName("표기 차이 별칭 — '다진 마늘'=다진마늘, '백식초'=식초 (실사용 발견, 종류가 다른 " +
            "맛소금/소금·진간장/간장은 합치지 않음)")
    void aliasesResolveSpellingVariants() {
        var c = candidate("a", List.of("다진 마늘", "백식초", "맛소금"));
        var match = RecommendRules.match(c, Set.of());

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
                RecommendRules.match(complete, Set.of("두부")),
                RecommendRules.match(seasoningOnly, Set.of("두부")),
                RecommendRules.match(missingFar, Set.of("두부")),
                RecommendRules.match(missingTwo, Set.of("두부")),
                RecommendRules.match(missingOne, Set.of("두부")));

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
        var match = RecommendRules.match(c, Set.of("두부"));

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
                .mapToObj(i -> RecommendRules.match(candidate("c" + i, List.of("두부")), Set.of("두부")))
                .toList();

        var sections = RecommendRules.bucket(matches);

        assertEquals(5, sections.complete().size());
    }
}
