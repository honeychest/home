// [AGENT] 유튜브 메타 조회·등록 정책 설정 seam — 분리 규율 5 (recipe 전용 설정 그룹)
package com.chs.gikka.registration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * gikka.youtube.* — 유튜브에서 무엇을 받아오고 무엇을 등록 단계에서 자르는가.
 * (2026-07-26 GikkaMediaProperties 분할로 신설)
 *
 * <p>apiKey: YouTube Data API 는 <b>구형 클라우드 키(AIza 접두)</b>만 받는다 — Gemini 의
 * AI Studio 신형 키(AQ.)와 겸용 불가 (2026-07-12 실측). 그래서 키가 두 그룹으로 갈려 있다
 * (Gemini 쪽은 {@code gikka.llm.api-key}). 비면 메타 조회만 생략된다 (로컬 개발 편의).
 *
 * <p>maxVideoMinutes: 길이 컷 (CONTEXT.md 2026-07-12 확정 = 7분). 초과 영상은 LLM 호출 없이
 * TOO_LONG 으로 기록만 남긴다. 유튜브에서 받은 duration 으로 판정하므로 여기 함께 둔다 —
 * 메타를 새로 받는 세 경로(신규 등록·재분석·REMOVED 복구)가 같은 규칙을 쓴다.
 */
@ConfigurationProperties(prefix = "gikka.youtube")
public class GikkaYoutubeProperties {

    private String apiKey = "";
    private int maxVideoMinutes = 7;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public int getMaxVideoMinutes() {
        return maxVideoMinutes;
    }

    public void setMaxVideoMinutes(int maxVideoMinutes) {
        this.maxVideoMinutes = maxVideoMinutes;
    }
}
