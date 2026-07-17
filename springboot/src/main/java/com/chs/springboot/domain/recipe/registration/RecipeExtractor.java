// [AGENT] 영상 분류+레시피 추출 인터페이스 — 분리 규율 4 (Gemini 호출 격리, 정책 변경 시 구현체만 교체)
// 6차 릴스·틱톡은 yt-dlp 다운로드 → File API 업로드 구현체가 이 자리에 추가된다.
package com.chs.springboot.domain.recipe.registration;

import java.util.List;

public interface RecipeExtractor {

    /** 모델·프롬프트 세대 — 올리면 구버전 요약만 골라 재생성 가능 (확장성 선반영) */
    int SUMMARY_VERSION = 1;

    /** category: RECIPE/TIP/ETC. RECIPE 가 아니면 레시피 필드는 null 이고 summary(요점 2~3문장)가 채워진다
        (2026-07-13 확정 — 같은 호출이라 추가 비용 0). tags 는 검색용 키워드 — 전 분류 공통.
        transcriptChars: 로컬 파이프라인이 whisper 로 뽑은 음성 전사 글자 수 (2026-07-14 확정,
        pattern-raw-signal). Gemini 단독 호출(로컬 불가로 폴백)이면 null — "로컬이 안 돌았다"는
        사실 그대로. RECIPE 채택 여부와 무관하게 로컬이 한 번이라도 돌았으면 채워짐
        (HybridRecipeExtractor 가 TIP/ETC 로 버려지는 로컬 결과에서도 이 값만은 이어받음).
        이 raw 값에서 경고 문구를 만드는 판정은 RegistrationRules.analysisSignals() 가 전담 —
        여기·추출기들은 사실만 나른다.
        confidentSeasonings (2026-07-17 5차-4 슬라이스1-C): ingredients 중 모델이 "명백한 양념"이라고
        확신한 이름들. 워커가 재료 사전에서 아직 판정 전(PENDING)인 것만 자동 CONFIRMED_SEASONING 으로
        올린다(오너가 이미 정한 것은 안 건드림). 확신 없는 것은 여기 안 넣어 PENDING=MAIN 안전 기본값
        으로 남고 오너·AI 점검이 나중에 판정한다. RECIPE 가 아니면 빈 목록 */
    record ExtractionResult(String category, String name, List<String> ingredients,
                            Integer cookMinutes, List<String> steps,
                            String summary, List<String> tags, Integer transcriptChars,
                            List<String> confidentSeasonings) {

        /** transcriptChars 만 교체한 복사본 — Hybrid 가 최종 채택 결과에 로컬의 원시 신호를 이어붙일 때 사용 */
        ExtractionResult withTranscriptChars(Integer chars) {
            return new ExtractionResult(category, name, ingredients, cookMinutes, steps, summary, tags, chars,
                    confidentSeasonings);
        }
    }

    /**
     * 일시적 실패 — Gemini 쪽 사정으로 지금 당장은 안 되지만 곧 풀릴 상황 (2026-07-13 확정,
     * 실측: 429 무료 한도 + 503 "high demand" 과부하 + 타임아웃 전부 이 성격으로 관찰됨).
     * 영상 자체의 문제가 아니므로 워커가 시도 횟수 안 깎고 대기 후 자동 재개한다.
     */
    class TransientFailureException extends RuntimeException {
        public TransientFailureException(String message) {
            super(message);
        }
    }

    /** description: 유튜브 설명란(본문). 재료가 원문으로 적힌 경우가 많아 최우선 활용 (2026-07-13 확정) */
    ExtractionResult extract(String videoUrl, String description);
}
