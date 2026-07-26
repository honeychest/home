// [AGENT] 재료 신고 → 전력 재분석 설정 seam — 분리 규율 5 (recipe 전용 설정 그룹)
package com.chs.springboot.domain.recipe.registration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * gikka.report.* — 재료 신고가 재분석으로 승격되는 조건 (2026-07-18 확정, CONTEXT.md "재료 신고" 절).
 * (2026-07-26 GikkaMediaProperties 분할로 신설 — "미디어" 라는 이름 아래 있을 값이 아니었다)
 *
 * <p>analyzeThreshold: 재분석을 트리거하는 <b>서로 다른 신고자 수</b>. 지금은 오너 혼자라 1 —
 * 공개 시 2→10 처럼 설정만 올려 노이즈를 거른다(코드 무수정, 사용자 확정).
 *
 * <p>maxRuns: 같은 (영상, 재료)의 재분석 실행 상한 — "신고→같은 결과→재신고" 무한 루프(무료 한도
 * 낭비) 차단. 상한 도달 후의 신고는 기록만 남는다("재분석으로 못 고치는 부류"라는 관찰 데이터가 됨).
 */
@ConfigurationProperties(prefix = "gikka.report")
public class GikkaReportProperties {

    private int analyzeThreshold = 1;
    private int maxRuns = 2;

    public int getAnalyzeThreshold() {
        return analyzeThreshold;
    }

    public void setAnalyzeThreshold(int analyzeThreshold) {
        this.analyzeThreshold = analyzeThreshold;
    }

    public int getMaxRuns() {
        return maxRuns;
    }

    public void setMaxRuns(int maxRuns) {
        this.maxRuns = maxRuns;
    }
}
