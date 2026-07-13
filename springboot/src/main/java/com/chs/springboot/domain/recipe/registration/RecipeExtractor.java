// [AGENT] 영상 분류+레시피 추출 인터페이스 — 분리 규율 4 (Gemini 호출 격리, 정책 변경 시 구현체만 교체)
// 6차 릴스·틱톡은 yt-dlp 다운로드 → File API 업로드 구현체가 이 자리에 추가된다.
package com.chs.springboot.domain.recipe.registration;

import java.util.List;

public interface RecipeExtractor {

    /** 모델·프롬프트 세대 — 올리면 구버전 요약만 골라 재생성 가능 (확장성 선반영) */
    int SUMMARY_VERSION = 1;

    /** category: RECIPE/TIP/ETC. RECIPE 가 아니면 레시피 필드는 null 이고 summary(요점 2~3문장)가 채워진다
        (2026-07-13 확정 — 같은 호출이라 추가 비용 0). tags 는 검색용 키워드 — 전 분류 공통 */
    record ExtractionResult(String category, String name, List<String> ingredients,
                            Integer cookMinutes, List<String> steps,
                            String summary, List<String> tags) {
    }

    /** 429(무료 한도) 전용 — 워커가 대기 후 자동 재개한다 (재시도 횟수 소모 없음) */
    class RateLimitedException extends RuntimeException {
        public RateLimitedException(String message) {
            super(message);
        }
    }

    ExtractionResult extract(String videoUrl);
}
