// [AGENT] 등록 판정 — DB·HTTP 없이 검증하는 순수 모듈 (rankFrequent 패턴, PLAYBOOK 관례 3)
// 컨트롤러는 이 판정의 결과만 사용한다. 규칙 변경·테스트는 이 파일과 RegistrationRulesTest 에서만.
package com.chs.springboot.domain.recipe.registration;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

final class RegistrationRules {

    static final String STATUS_WAITING = "WAITING";
    static final String STATUS_TOO_LONG = "TOO_LONG";

    private RegistrationRules() {
    }

    /**
     * 길이 컷 (CONTEXT.md 2026-07-12 확정 = 7분): 초과 영상은 Gemini 호출 없이 TOO_LONG.
     * 길이를 모르면(메타 실패, null) 컷하지 않고 분석을 시도한다 — 막는 것보다 낫다.
     */
    static String initialStatus(Integer durationSeconds, int maxVideoMinutes) {
        boolean tooLong = durationSeconds != null && durationSeconds > maxVideoMinutes * 60;
        return tooLong ? STATUS_TOO_LONG : STATUS_WAITING;
    }

    /** 재생목록 일괄 등록용: 메타 목록을 영상 ID 로 색인 (중복 ID 는 첫 항목 우선) */
    static Map<String, VideoMetadataClient.VideoMetadata> metadataById(
            List<VideoMetadataClient.VideoMetadata> metas) {
        return metas.stream().collect(Collectors.toMap(
                VideoMetadataClient.VideoMetadata::videoId, Function.identity(), (first, dup) -> first));
    }
}
