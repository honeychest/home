// [AGENT] 추천 3단계 판정 고정 (DB·HTTP 없는 순수 테스트) — CONTEXT.md 4차 확정(2026-07-14 grill-me)
// 2026-07-17 5차-4 슬라이스1: classify/match 가 양념 이름 집합을 인자로 받도록 바뀜(사전 DB화).
// 2026-07-17 5차-4 슬라이스2: 셋(matchKeys·seasoning·basic)이 늘 같이 다녀 Dictionary 로 묶임 +
// 매칭이 이름 정확 일치에서 match_key 비교로 바뀜.
// 순수 모듈 규율 유지 — 테스트는 DB 대신 Dictionary 값을 직접 주입해 판정을 고정한다.
package com.chs.springboot.domain.recipe.recommend;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /** 그룹을 안 쓰는 사전 — matchKeys 가 비면 모든 이름의 키가 자기 이름이라 슬라이스1(이름 정확
        일치)과 동작이 같다. 그룹 매칭 테스트만 자기 사전을 따로 만든다. */
    private static final RecommendRules.Dictionary DICT =
            new RecommendRules.Dictionary(Map.of(), SEASONINGS, BASICS);

    private static RecommendRules.Candidate candidate(String id, List<String> ingredients) {
        return new RecommendRules.Candidate(
                id, "https://youtu.be/" + id, id + "-title", "http://t/" + id, ingredients, 10, List.of("끓인다"));
    }

    @Test
    @DisplayName("재료·양념 다 있으면 완전 가능")
    void completeWhenNothingMissing() {
        var c = candidate("a", List.of("두부", "고추장"));
        var match = RecommendRules.match(c, Set.of("두부", "고추장"), Set.of(), DICT);

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
        var match = RecommendRules.match(c, Set.of("두부"), Set.of(), DICT);

        assertTrue(match.isComplete());
        assertTrue(match.missingSeasoning().isEmpty());
        assertTrue(match.missingMain().isEmpty());
    }

    @Test
    @DisplayName("BASIC 이 SEASONING 을 이긴다 — 두 집합에 같은 이름이 들어가는 사고가 나도 "
            + "'늘 있다'가 우선이라 부족분에 안 샌다")
    void basicWinsOverSeasoning() {
        assertEquals(RecommendRules.Tier.BASIC,
                RecommendRules.classify("소금", new RecommendRules.Dictionary(Map.of(), Set.of("소금"), Set.of("소금"))));
    }

    @Test
    @DisplayName("재료는 다 있고 양념만 없으면 양념만 부족 — 재료 부족 판정에 안 걸림")
    void seasoningOnlyWhenMainPresent() {
        var c = candidate("a", List.of("두부", "고추장"));
        var match = RecommendRules.match(c, Set.of("두부"), Set.of(), DICT);

        assertTrue(match.isSeasoningOnly());
        assertEquals(List.of("고추장"), match.missingSeasoning());
        assertTrue(match.missingMain().isEmpty());
    }

    @Test
    @DisplayName("재료가 몇 개든 없으면 재료 부족 — 부족 개수를 그대로 담는다")
    void missingMainCarriesCount() {
        var c = candidate("a", List.of("두부", "대파", "고추장"));
        var match = RecommendRules.match(c, Set.of(), Set.of(), DICT);

        assertTrue(match.isMissingMain());
        assertEquals(List.of("두부", "대파"), match.missingMain());
        assertEquals(List.of("고추장"), match.missingSeasoning());
    }

    @Test
    @DisplayName("재료가 4개 이상 없어도 재료 부족 섹션에 걸린다 (2026-07-14 확정 — 상한으로 제외하면 " +
            "완전 매칭이 하나도 없을 때 추천이 통째로 안 보이는 문제가 실사용에서 발견됨)")
    void manyMissingStillCountsAsMissingMain() {
        var c = candidate("a", List.of("두부", "대파", "돼지고기", "김치"));
        var match = RecommendRules.match(c, Set.of(), Set.of(), DICT);

        assertTrue(match.isMissingMain());
        assertEquals(4, match.missingMain().size());
    }

    @Test
    @DisplayName("사전에 있는 이름만 양념 — 사전에 없는 이름은 판정 전이라 MAIN(안전 기본값). "
            + "'굴소스'가 그 사례: 사전 도입 전엔 코드 상수 19개 밖이라 주재료로 잡혀 추천이 밀렸다")
    void unknownNamesAreMain() {
        var c = candidate("a", List.of("고추장", "다진 마늘", "굴소스"));
        var match = RecommendRules.match(c, Set.of(), Set.of(), DICT);

        assertEquals(List.of("고추장"), match.missingSeasoning()); // 사전의 SEASONING
        assertEquals(List.of("굴소스"), match.missingMain());       // 사전에 없음 → MAIN
        // "다진 마늘"(BASIC)은 어느 쪽에도 안 들어간다 — 늘 있다고 간주
    }

    @Test
    @DisplayName("섹션 분류·정렬: 재료 부족은 적은 개수 순이 최우선 (상한 없이 멀리 부족한 것도 뒤에 포함)")
    void bucketsSortsAndCaps() {
        var complete = candidate("complete", List.of("두부"));
        var seasoningOnly = candidate("seasoning", List.of("두부", "고추장"));
        var missingFar = candidate("missing-far", List.of("두부", "대파", "돼지고기", "김치"));
        var missingTwo = candidate("missing-two", List.of("두부", "대파", "김치"));
        var missingOne = candidate("missing-one", List.of("두부", "대파"));

        var matches = List.of(
                RecommendRules.match(complete, Set.of("두부"), Set.of(), DICT),
                RecommendRules.match(seasoningOnly, Set.of("두부"), Set.of(), DICT),
                RecommendRules.match(missingFar, Set.of("두부"), Set.of(), DICT),
                RecommendRules.match(missingTwo, Set.of("두부"), Set.of(), DICT),
                RecommendRules.match(missingOne, Set.of("두부"), Set.of(), DICT));

        var sections = RecommendRules.bucket(matches, Set.of(), 1L);

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
        var match = RecommendRules.match(c, Set.of("두부"), Set.of(), DICT);

        assertEquals(
                List.of(
                        new RecommendRules.IngredientStatus("두부", true),
                        new RecommendRules.IngredientStatus("대파", false),
                        new RecommendRules.IngredientStatus("소금", true),
                        new RecommendRules.IngredientStatus("고추장", false)),
                match.ingredientStatuses());
    }

    /* ── 그룹 매칭 (2026-07-17 5차-4 슬라이스2) ──
       실측 근거: 막힌 레시피 107개 중 상당수가 냉장고에 이미 있는 것의 다른 이름이었다 —
       "평생 떡볶이 ← 밀떡 부족"(냉장고에 밀떡 있음), "짬뽕맛 라면 ← 매운 라면 부족"(신라면 있음). */

    /** 계란 계열을 "계란"으로, 라면 계열을 "라면"으로 묶은 사전. 대표는 자기 자신을 가리킨다. */
    private static final RecommendRules.Dictionary GROUPED = new RecommendRules.Dictionary(
            Map.of("계란", "계란", "계란 2개", "계란", "달걀", "계란",
                    "라면", "라면", "신라면", "라면", "라면 건더기스프", "라면",
                    "진간장", "진간장"),
            SEASONINGS, BASICS);

    @Test
    @DisplayName("그룹 매칭: 냉장고의 '계란'이 레시피의 '계란 2개'를 덮는다 — 수량 표기 때문에 "
            + "부족으로 잡히던 것이 사라진다(프롬프트를 조여도 이미 쌓인 데이터엔 소급이 안 되지만 "
            + "match_key 는 재분석 없이 소급된다 — 2026-07-17 확정)")
    void groupedNamesMatchThroughRepresentative() {
        var c = candidate("a", List.of("계란 2개", "라면 건더기스프"));
        var fridgeKeys = RecommendRules.keysOf(Set.of("계란", "신라면"), GROUPED);

        var match = RecommendRules.match(c, fridgeKeys, Set.of(), GROUPED);

        assertTrue(match.isComplete()); // 신라면 → 라면 그룹이라 건더기스프도 있는 것으로 친다
    }

    @Test
    @DisplayName("안 묶인 이름은 예전 그대로 엄격 매칭 — '진간장'은 '간장'이 있어도 부족이다 "
            + "(실제로 다른 재료라 묶지 않는다. 오병합은 없는 재료를 있다고 말하게 된다)")
    void ungroupedNamesStayStrict() {
        var c = candidate("a", List.of("진간장"));
        var fridgeKeys = RecommendRules.keysOf(Set.of("간장"), GROUPED);

        var match = RecommendRules.match(c, fridgeKeys, Set.of(), GROUPED);

        assertFalse(match.isComplete());
        assertEquals(List.of("진간장"), match.missingMain());
    }

    @Test
    @DisplayName("부족분에 담기는 이름은 대표가 아니라 레시피 원문 그대로 — 화면엔 영상이 말한 "
            + "이름이 보여야 한다(재료 원문 보존 원칙)")
    void missingKeepsOriginalNameNotRepresentative() {
        var c = candidate("a", List.of("계란 2개"));

        var match = RecommendRules.match(c, Set.of(), Set.of(), GROUPED);

        assertEquals(List.of("계란 2개"), match.missingMain());
    }

    @Test
    @DisplayName("묶인 멤버의 양념 여부는 대표를 따른다 — 저장소가 멤버 이름까지 펼쳐 주므로 "
            + "규칙은 이름 집합만 보면 된다(멤버 자신의 status 는 매칭에 안 쓰인다)")
    void memberInheritsRepresentativeTier() {
        // 저장소의 조인 결과를 그대로 흉내: "고추장"이 SEASONING 이면 멤버 "고추장 2스푼"도 집합에 들어온다
        var dict = new RecommendRules.Dictionary(
                Map.of("고추장", "고추장", "고추장 2스푼", "고추장"),
                Set.of("고추장", "고추장 2스푼"), BASICS);
        var c = candidate("a", List.of("고추장 2스푼"));

        var match = RecommendRules.match(c, Set.of(), Set.of(), dict);

        assertTrue(match.isSeasoningOnly());
        assertEquals(List.of("고추장 2스푼"), match.missingSeasoning());
    }

    /* ── 상한·정렬 개편 (2026-07-18 사용자 확정 — 내 것 10 + 남의 것 10, 임박 우선, 일일 셔플) ── */

    private static List<RecommendRules.Match> completeMatches(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> RecommendRules.match(
                        candidate("c" + i, List.of("두부")), Set.of("두부"), Set.of(), DICT))
                .toList();
    }

    @Test
    @DisplayName("섹션 상한은 내 것 10 + 남의 것 10 을 따로 센다 — 남의 것이 많아도 내 것 자리를 안 뺏는다")
    void capsMineAndOthersSeparately() {
        var matches = completeMatches(30); // c0~c29 전부 완전 가능
        Set<String> mine = java.util.stream.IntStream.range(0, 15)
                .mapToObj(i -> "c" + i).collect(java.util.stream.Collectors.toSet()); // c0~c14 = 내 것

        var sections = RecommendRules.bucket(matches, mine, 1L);

        assertEquals(20, sections.complete().size());
        assertEquals(10, sections.complete().stream()
                .filter(m -> mine.contains(m.candidate().videoId())).count());
    }

    @Test
    @DisplayName("임박 재료를 쓰는 레시피가 위로 온다 — '임박 때문에 항상 보이는 건 OK'의 구현")
    void expiringUsedRanksFirst() {
        var plain = RecommendRules.match(candidate("plain", List.of("두부")), Set.of("두부"), Set.of(), DICT);
        var usesExpiring = RecommendRules.match(
                candidate("expiring", List.of("두부")), Set.of("두부"), Set.of("두부"), DICT);

        assertEquals(1, usesExpiring.expiringUsed());
        for (long seed = 0; seed < 5; seed++) { // 어떤 셔플 시드에서도 임박이 항상 먼저
            var sections = RecommendRules.bucket(List.of(plain, usesExpiring), Set.of(), seed);
            assertEquals("expiring", sections.complete().get(0).candidate().videoId());
        }
    }

    @Test
    @DisplayName("재료 부족 섹션은 부족 개수가 임박 가중치보다 우선 — 임박을 써도 더 많이 부족하면 뒤")
    void missingCountBeatsExpiring() {
        var lessMissing = RecommendRules.match(
                candidate("less", List.of("두부", "대파")), Set.of("두부"), Set.of(), DICT);
        var moreMissingButExpiring = RecommendRules.match(
                candidate("more", List.of("두부", "대파", "김치")), Set.of("두부"), Set.of("두부"), DICT);

        var sections = RecommendRules.bucket(List.of(moreMissingButExpiring, lessMissing), Set.of(), 1L);

        assertEquals(List.of("less", "more"),
                sections.needsIngredients().stream().map(m -> m.candidate().videoId()).toList());
    }

    @Test
    @DisplayName("동점 셔플: 같은 시드면 항상 같은 순서(하루 동안 목록 고정), 시드가 바뀌면 순서가 돈다")
    void tieShuffleIsSeededDaily() {
        var matches = completeMatches(30);

        var today = RecommendRules.bucket(matches, Set.of(), 20260718L);
        var todayAgain = RecommendRules.bucket(matches, Set.of(), 20260718L);
        var tomorrow = RecommendRules.bucket(matches, Set.of(), 20260719L);

        List<String> order = today.complete().stream().map(m -> m.candidate().videoId()).toList();
        assertEquals(order, todayAgain.complete().stream().map(m -> m.candidate().videoId()).toList());
        assertFalse(order.equals(
                tomorrow.complete().stream().map(m -> m.candidate().videoId()).toList()),
                "시드가 다르면 동점 순서가 달라져야 한다 (30개 동점이 우연히 같을 확률은 사실상 0)");
    }
}
