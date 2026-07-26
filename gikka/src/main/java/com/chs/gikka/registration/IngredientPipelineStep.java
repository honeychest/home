// [AGENT] 재료 사전 반영 절차의 한 단계 — 신규 재료 등록·자동 승격처럼 RECIPE 분석 직후
// dictionary 에 적용되는 판정들을 순서가 있는 목록으로 다루기 위한 확장점 (2026-07-18 확정).
// RegistrationWorker.INGREDIENT_PIPELINE 이 이 인터페이스의 목록을 순서대로 실행한다 —
// 단계를 추가·제거·순서 교체하려면 그 목록 한 줄만 바꾸면 된다. name() 은 나중에 오너 화면에
// 현재 절차 순서를 그대로 나열해 보여줄 때 쓸 표시용 이름이다(아직 화면은 없음).
package com.chs.gikka.registration;

import com.chs.gikka.dictionary.IngredientDictionaryRepository;

public interface IngredientPipelineStep {

    /** 오너 화면에 나열할 표시용 이름 */
    String name();

    void apply(RecipeExtractor.ExtractionResult result, IngredientDictionaryRepository dictionary);
}
