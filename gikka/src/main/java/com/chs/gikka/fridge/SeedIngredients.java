// [AGENT] 자주 사는 재료 시드 12개 — 유일한 원본 (2차 저장소 교체 때 프론트 seedIngredients.ts 는 삭제됨)
package com.chs.gikka.fridge;

import java.util.List;

public final class SeedIngredients {

    /** 한국 가정 상비 재료 — 실제 추가 횟수가 쌓이면 순위가 사용자 것으로 자연 교체 */
    public static final List<String> LIST = List.of(
            "계란", "대파", "양파", "마늘",
            "두부", "감자", "당근", "애호박",
            "버섯", "돼지고기", "닭고기", "김치"
    );

    private SeedIngredients() {
    }
}
