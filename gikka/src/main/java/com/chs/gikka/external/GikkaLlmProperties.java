// [AGENT] LLM 호출 설정 seam — 분리 규율 5 (recipe 전용 설정 그룹)
package com.chs.gikka.external;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * gikka.llm.* — Gemini 호출에만 쓰이는 값. (2026-07-26 GikkaMediaProperties 분할로 신설)
 *
 * <p>분할 전에는 유튜브 키·길이컷·텔레그램·신고 임계값까지 한 클래스(gikka.media.*)에 있었고,
 * 값 하나가 필요한 쪽도 그 열 개짜리 덩어리를 통째로 주입받았다. 특히 X 영상 다운로드
 * (xdownload)가 호스트 주소 하나 때문에 registration 을 import 하고 있었다.
 *
 * <p>키가 두 개인 이유 (2026-07-12 실측): 구글이 키 체계를 갈랐다 — Gemini(generativelanguage)는
 * AI Studio 신형 키(AQ. 접두)만, YouTube Data API 는 구형 클라우드 키(AIza 접두)만 받는다.
 * 한 키로 둘 다 호출하는 것이 더 이상 불가능해서, 유튜브 키는 {@code gikka.youtube.*} 로 갈라져 있다.
 * 둘 다 gikka 프로젝트 소속·무료 (결제 연결 금지 원칙 동일).
 *
 * <p>apiKey 가 비면 분석 워커가 쉰다 (키 없는 환경 = 로컬 개발 기본).
 */
@ConfigurationProperties(prefix = "gikka.llm")
public class GikkaLlmProperties {

    private String apiKey = "";
    // 2.5-flash 는 신규 키에 폐쇄됨 (2026-07-12 실측 404) — 3.5-flash 가 현행 안정판
    private String model = "gemini-3.5-flash";
    // 고정 모델이 또 폐쇄(404)되면 이 모델로 자동 전환 + 텔레그램 알림 (2026-07-12 승인).
    // latest 상시 사용은 품질이 소리 없이 바뀌어 비추천 — 폐쇄 시 비상용으로만.
    private String fallbackModel = "gemini-flash-latest";
    // 인스턴스 합산 호출 간격 하한 — 무료 등급 분당 한도(약 10회) 보호
    private int minIntervalSeconds = 6;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getFallbackModel() {
        return fallbackModel;
    }

    public void setFallbackModel(String fallbackModel) {
        this.fallbackModel = fallbackModel;
    }

    public int getMinIntervalSeconds() {
        return minIntervalSeconds;
    }

    public void setMinIntervalSeconds(int minIntervalSeconds) {
        this.minIntervalSeconds = minIntervalSeconds;
    }
}
