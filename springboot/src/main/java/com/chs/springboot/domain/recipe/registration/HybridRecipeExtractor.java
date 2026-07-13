// [AGENT] 로컬 우선 라우팅 (2026-07-14 grill-me 확정): 로컬(무료)로 먼저 분류+추출을 시도해
// category==RECIPE 면 그대로 채택 — 실측(참치무조림·비빔국수)상 로컬이 Gemini 와 거의 동급이었음.
// RECIPE 가 아니면(TIP/ETC) 결과를 버리고 Gemini 로 다시 분석한다 — 실측(BLG e스포츠 영상)에서
// 로컬이 고유명사를 지어내는 할루시네이션을 확인해, 세계 지식이 중요한 카테고리는 신뢰 낮음.
// 로컬 파이프라인 자체가 불가하면(서비스 다운 등) Gemini 로 전체를 넘긴다 — 가용성 안전망.
// RegistrationWorker 는 RecipeExtractor 인터페이스만 알면 되므로 무수정 (분리 규율 4).
package com.chs.springboot.domain.recipe.registration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Primary
@Component
public class HybridRecipeExtractor implements RecipeExtractor {

    private static final Logger log = LoggerFactory.getLogger(HybridRecipeExtractor.class);

    private final LocalRecipeExtractor local;
    private final GeminiRecipeExtractor gemini;

    public HybridRecipeExtractor(LocalRecipeExtractor local, GeminiRecipeExtractor gemini) {
        this.local = local;
        this.gemini = gemini;
    }

    @Override
    public ExtractionResult extract(String videoUrl, String description) {
        try {
            ExtractionResult localResult = local.extract(videoUrl, description);
            if ("RECIPE".equals(localResult.category())) {
                return localResult;
            }
            log.info("[gikka] 로컬 분류 {} — 세계 지식 신뢰도 문제로 Gemini 재분석", localResult.category());
        } catch (LocalRecipeExtractor.LocalUnavailableException e) {
            log.warn("[gikka] 로컬 추출 불가 — Gemini 로 전체 폴백: {}", e.getMessage());
        }
        return gemini.extract(videoUrl, description);
    }
}
