// [AGENT] 자동 판정의 적용 가능 제안 필터 고정 — DB·HTTP 없는 순수 테스트.
// 자동 적용은 사람 확인이 없는 경로라, "무엇을 절대 안 건드리는가"(오너 판정·멤버·지어낸 이름)를
// 여기서 잠근다. 실제 적용의 PENDING 가드는 SQL(updateStatusIfPending·autoMergeVariant)이
// 한 번 더 지킨다 — 이중 방어.
package com.chs.springboot.domain.recipe.registration;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngredientAutoJudgeTest {

    private static IngredientDictionaryRepository.Entry entry(String name, String matchKey, String status) {
        return new IngredientDictionaryRepository.Entry(name, matchKey, status);
    }

    private static final List<IngredientDictionaryRepository.Entry> DICT = List.of(
            entry("계란", "계란", "CONFIRMED_MAIN"),
            entry("달걀", "계란", "PENDING"),          // 멤버 (이미 묶임)
            entry("굴소스", "굴소스", "PENDING"),       // 판정 대상
            entry("새 변형", "새 변형", "PENDING"),     // 판정 대상
            entry("간장", "간장", "CONFIRMED_BASIC")); // 오너가 이미 판정

    @Test
    @DisplayName("분류 제안: PENDING 대표의 SEASONING/BASIC 만 적용 대상 — MAIN 은 무의미해서 버림")
    void classifyOnlyPendingRepresentatives() {
        var proposals = List.of(
                new IngredientAuditor.Proposal("굴소스", IngredientAuditor.TIER_SEASONING, null),
                new IngredientAuditor.Proposal("새 변형", IngredientAuditor.TIER_MAIN, null));

        var applicable = IngredientAutoJudge.applicableProposals(proposals, DICT);

        assertEquals(1, applicable.size());
        assertEquals("굴소스", applicable.get(0).name());
    }

    @Test
    @DisplayName("오너가 이미 판정한 이름·이미 묶인 멤버·사전에 없는 이름은 절대 안 건드린다")
    void neverTouchesJudgedMembersOrInventedNames() {
        var proposals = List.of(
                new IngredientAuditor.Proposal("간장", IngredientAuditor.TIER_SEASONING, null), // 판정 완료
                new IngredientAuditor.Proposal("달걀", IngredientAuditor.TIER_SEASONING, null), // 멤버
                new IngredientAuditor.Proposal("지어낸 이름", IngredientAuditor.TIER_SEASONING, null));

        assertTrue(IngredientAutoJudge.applicableProposals(proposals, DICT).isEmpty());
    }

    @Test
    @DisplayName("병합 제안: 대상이 실재하는 대표일 때만 — 멤버를 대표로 삼으면(체인) 버림")
    void mergeTargetMustBeExistingRepresentative() {
        var proposals = List.of(
                new IngredientAuditor.Proposal("새 변형", null, "계란"),   // 정상 (대표)
                new IngredientAuditor.Proposal("굴소스", null, "달걀"),    // 멤버가 대표 — 체인 방지
                new IngredientAuditor.Proposal("굴소스", null, "없는대표"));

        var applicable = IngredientAutoJudge.applicableProposals(proposals, DICT);

        assertEquals(1, applicable.size());
        assertEquals("계란", applicable.get(0).mergeInto());
    }
}
