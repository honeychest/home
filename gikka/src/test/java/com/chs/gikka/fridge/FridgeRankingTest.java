// [AGENT] 자주 사는 재료 순위 규칙 고정 — 기준은 프론트 구 localStorage 구현체의 관찰 동작
package com.chs.gikka.fridge;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.chs.gikka.fridge.FridgeRepository.rankFrequent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FridgeRankingTest {

    @Test
    @DisplayName("통계가 없으면 시드 12개가 그대로 나온다 (첫 사용)")
    void seedsOnlyWhenNoStats() {
        assertEquals(SeedIngredients.LIST, rankFrequent(List.of(), SeedIngredients.LIST, 12));
    }

    @Test
    @DisplayName("추가 횟수가 쌓인 재료가 시드를 밀어내고 앞으로 온다")
    void statsOutrankSeeds() {
        List<String> result = rankFrequent(List.of(
                        new FridgeRepository.Stat("고수", 3, false),
                        new FridgeRepository.Stat("계란", 1, false)),
                SeedIngredients.LIST, 12);

        assertEquals("고수", result.get(0));   // 횟수 3 — 최상단
        assertEquals("계란", result.get(1));   // 횟수 1 — 시드보다 앞
        assertTrue(result.contains("대파"));    // 나머지 시드는 횟수 0으로 병합
        assertEquals(12, result.size());       // limit 준수 (시드 11 + 고수 = 13 중 12)
    }

    @Test
    @DisplayName("숨김 처리된 재료는 시드여도 다시 올라오지 않는다 (편집 모드 제거)")
    void hiddenExcludedEvenIfSeed() {
        List<String> result = rankFrequent(
                List.of(new FridgeRepository.Stat("계란", 5, true)),
                SeedIngredients.LIST, 12);

        assertFalse(result.contains("계란"));
    }

    @Test
    @DisplayName("limit 만큼만 반환한다")
    void respectsLimit() {
        assertEquals(5, rankFrequent(List.of(), SeedIngredients.LIST, 5).size());
    }
}
