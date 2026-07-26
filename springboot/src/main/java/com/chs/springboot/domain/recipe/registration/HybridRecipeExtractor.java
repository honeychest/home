// [AGENT] 로컬 우선 라우팅 (2026-07-14 grill-me 확정): 로컬(무료)로 먼저 분류+추출을 시도해
// category==RECIPE 면 그대로 채택 — 실측(참치무조림·비빔국수)상 로컬이 Gemini 와 거의 동급이었음.
// RECIPE 가 아니면(TIP/ETC) 결과를 버리고 Gemini 로 다시 분석한다 — 실측(BLG e스포츠 영상)에서
// 로컬이 고유명사를 지어내는 할루시네이션을 확인해, 세계 지식이 중요한 카테고리는 신뢰 낮음.
// 로컬 파이프라인 자체가 불가하면(서비스 다운 등) Gemini 로 전체를 넘긴다 — 가용성 안전망.
// Gemini 검증 호출이 일시적으로 실패하면(429·503·타임아웃) 로컬이 이미 낸 TIP/ETC 결과가
// 있는 경우 그걸 그대로 채택한다 (2026-07-14 확정 — Gemini 무료 등급 과부하로 무한 대기하는
// 대신, 이미 손에 쥔 로컬 결과를 쓰는 게 낫다는 판단. 품질 경고 문구는 안 붙임 — 필요하면
// 기존 수동 재분석 버튼으로 나중에 Gemini 재검증 가능). 로컬 자체가 불가해 대체 결과가 없는
// 경우는 기존처럼 예외를 그대로 던져 RegistrationWorker 의 재시도 루프를 탄다.
// RegistrationWorker 는 RecipeExtractor 인터페이스만 알면 되므로 무수정 (분리 규율 4).
package com.chs.springboot.domain.recipe.registration;

import com.chs.springboot.domain.recipe.external.LocalUnavailableException;
import com.chs.springboot.domain.recipe.external.TransientFailureException;

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

    /** 신고 재점검 (2026-07-18): 힌트가 있으면 로컬 라우팅을 건너뛰고 Gemini 직행 — "가진 역량
        총동원" 전력 분석(사용자 확정). 저빈도 경로라 Gemini 한도 부담 없음. 일시적 실패는 그대로
        전파돼 워커의 백오프 재시도를 탄다(힌트는 video.report_ingredient 에 남아 재시도에도 유지). */
    @Override
    public ExtractionResult extract(String videoUrl, String title, String description,
                                    String reportedIngredient) {
        if (reportedIngredient == null || reportedIngredient.isBlank()) {
            return extract(videoUrl, title, description);
        }
        return gemini.extract(videoUrl, title, description, reportedIngredient);
    }

    @Override
    public ExtractionResult extract(String videoUrl, String title, String description) {
        Integer transcriptChars = null; // 로컬이 아예 안 돌면 null 그대로 (2026-07-14, pattern-raw-signal)
        ExtractionResult localFallback = null; // Gemini 검증 실패 시 대신 쓸 로컬 TIP/ETC 결과
        try {
            ExtractionResult localResult = local.extract(videoUrl, title, description);
            transcriptChars = localResult.transcriptChars();
            if ("RECIPE".equals(localResult.category())) {
                return localResult;
            }
            log.info("[gikka] 로컬 분류 {} — 세계 지식 신뢰도 문제로 Gemini 재분석", localResult.category());
            localFallback = localResult;
        } catch (LocalUnavailableException e) {
            log.warn("[gikka] 로컬 추출 불가 — Gemini 로 전체 폴백: {}", e.getMessage());
        }
        // TIP/ETC 로 버려진 로컬 결과라도 transcriptChars(음성 인식 신호)는 최종 채택 결과에 이어붙인다 —
        // "이 영상에 음성 정보가 얼마나 있었나"는 어느 쪽이 채택되든 변하지 않는 사실이므로
        try {
            return gemini.extract(videoUrl, title, description).withTranscriptChars(transcriptChars);
        } catch (TransientFailureException e) {
            if (localFallback != null) {
                log.warn("[gikka] Gemini 검증 실패({}) — 로컬 {} 결과로 대신 완료: {}",
                        e.getMessage(), localFallback.category(), e.getMessage());
                return localFallback;
            }
            throw e; // 대체할 로컬 결과가 없음(로컬 자체 불가) — 기존 재시도 루프로
        }
    }
}
