// [AGENT] 재료 사전 AI 점검의 제안 파싱 고정 — Gemini 봉투가 GeminiJsonClient seam 으로 빠지면서
// parse 가 순수 함수(알맹이 배열 → 제안)로 남아 HTTP 없이 검증 가능해졌다 (2026-07-17 점검의 이득).
package com.chs.springboot.domain.recipe.registration;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IngredientAuditorTest {

    @Test
    @DisplayName("제안 파싱: BASIC/SEASONING/MAIN 을 그대로 담고, 이름이 빈 항목은 버린다")
    void parsesProposals() throws Exception {
        var array = new ObjectMapper().readTree("""
                [{"name":"고추장","tier":"SEASONING"},{"name":"소금","tier":"BASIC"},
                 {"name":"두부","tier":"MAIN"},{"name":"  ","tier":"SEASONING"}]
                """);

        var proposals = IngredientAuditor.parse(array);

        assertEquals(3, proposals.size());
        assertEquals("고추장", proposals.get(0).name());
        assertEquals("SEASONING", proposals.get(0).suggestedTier());
        assertEquals("소금", proposals.get(1).name());
        assertEquals("BASIC", proposals.get(1).suggestedTier());
        assertEquals("두부", proposals.get(2).name());
        assertEquals("MAIN", proposals.get(2).suggestedTier());
    }

    @Test
    @DisplayName("모르는 tier 는 전부 MAIN 으로 정규화 (안전 기본값 — 스키마가 enum 을 강제하지만 "
            + "모델이 어겨도 위험한 쪽으로 안 기울게)")
    void unknownTierBecomesMain() throws Exception {
        var array = new ObjectMapper().readTree("""
                [{"name":"고구마","tier":"???"}]
                """);

        var proposals = IngredientAuditor.parse(array);

        assertEquals("MAIN", proposals.get(0).suggestedTier());
    }

    @Test
    @DisplayName("묶기 제안이면 tier 는 버린다 — 묶이는 순간 양념 여부는 대표가 정하므로 둘을 "
            + "같이 제안하면 오너가 뭘 승인하는 건지 흐려진다 (2026-07-17 슬라이스2)")
    void mergeProposalDropsTier() throws Exception {
        var array = new ObjectMapper().readTree("""
                [{"name":"계란 2개","tier":"MAIN","mergeInto":"계란"}]
                """);

        var proposals = IngredientAuditor.parse(array);

        assertEquals("계란 2개", proposals.get(0).name());
        assertEquals("계란", proposals.get(0).mergeInto());
        assertEquals(null, proposals.get(0).suggestedTier());
    }

    @Test
    @DisplayName("빈 mergeInto·자기 자신에게 묶기는 '안 묶음'과 같은 뜻 — 분류 제안으로 남는다")
    void emptyOrSelfMergeIsNotAMerge() throws Exception {
        var array = new ObjectMapper().readTree("""
                [{"name":"고추장","tier":"SEASONING","mergeInto":""},
                 {"name":"두부","tier":"MAIN","mergeInto":"두부"}]
                """);

        var proposals = IngredientAuditor.parse(array);

        assertEquals(null, proposals.get(0).mergeInto());
        assertEquals("SEASONING", proposals.get(0).suggestedTier());
        assertEquals(null, proposals.get(1).mergeInto());
        assertEquals("MAIN", proposals.get(1).suggestedTier());
    }
}
