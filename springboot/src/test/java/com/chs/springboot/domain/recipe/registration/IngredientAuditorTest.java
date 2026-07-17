// [AGENT] 재료 사전 AI 점검의 제안 파싱 고정 — Gemini 봉투가 GeminiJsonClient seam 으로 빠지면서
// parse 가 순수 함수(알맹이 배열 → 제안)로 남아 HTTP 없이 검증 가능해졌다 (2026-07-17 점검의 이득).
package com.chs.springboot.domain.recipe.registration;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IngredientAuditorTest {

    @Test
    @DisplayName("제안 파싱: SEASONING/MAIN 을 그대로 담고, 이름이 빈 항목은 버린다")
    void parsesProposals() throws Exception {
        var array = new ObjectMapper().readTree("""
                [{"name":"간장","tier":"SEASONING"},{"name":"두부","tier":"MAIN"},{"name":"  ","tier":"SEASONING"}]
                """);

        var proposals = IngredientAuditor.parse(array);

        assertEquals(2, proposals.size());
        assertEquals("간장", proposals.get(0).name());
        assertEquals("SEASONING", proposals.get(0).suggestedTier());
        assertEquals("두부", proposals.get(1).name());
        assertEquals("MAIN", proposals.get(1).suggestedTier());
    }

    @Test
    @DisplayName("tier 가 SEASONING 이 아니면 전부 MAIN 으로 정규화 (안전 기본값)")
    void unknownTierBecomesMain() throws Exception {
        var array = new ObjectMapper().readTree("""
                [{"name":"고구마","tier":"???"}]
                """);

        var proposals = IngredientAuditor.parse(array);

        assertEquals("MAIN", proposals.get(0).suggestedTier());
    }
}
