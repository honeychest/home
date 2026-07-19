// [AGENT] gikka 영상 분석 설정 seam — 분리 규율 5 (recipe 전용 설정 그룹)
package com.chs.springboot.domain.recipe.registration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * gikka.media.* 설정.
 *
 * - 키가 두 개인 이유 (2026-07-12 실측): 구글이 키 체계를 갈랐다 —
 *   Gemini(generativelanguage)는 AI Studio 신형 키(AQ. 접두)만, YouTube Data API 는
 *   구형 클라우드 키(AIza 접두)만 받는다. 한 키로 둘 다 호출하는 것이 더 이상 불가능.
 *   둘 다 gikka 프로젝트 소속·무료 (결제 연결 금지 원칙 동일).
 * - geminiApiKey 가 비면 분석 워커가 쉬고, youtubeApiKey 가 비면 메타 조회만 생략 (로컬 개발 편의).
 * - maxVideoMinutes: 길이 컷 (CONTEXT.md 2026-07-12 확정 = 7분). 초과 영상은 Gemini 호출 없이 TOO_LONG.
 * - geminiMinIntervalSeconds: 인스턴스 합산 호출 간격 하한 — 무료 등급 분당 한도(약 10회) 보호.
 */
@ConfigurationProperties(prefix = "gikka.media")
public class GikkaMediaProperties {

    private String geminiApiKey = "";
    private String youtubeApiKey = "";
    // 2.5-flash 는 신규 키에 폐쇄됨 (2026-07-12 실측 404) — 3.5-flash 가 현행 안정판
    private String geminiModel = "gemini-3.5-flash";
    // 고정 모델이 또 폐쇄(404)되면 이 모델로 자동 전환 + 텔레그램 알림 (2026-07-12 승인).
    // latest 상시 사용은 품질이 소리 없이 바뀌어 비추천 — 폐쇄 시 비상용으로만.
    private String geminiFallbackModel = "gemini-flash-latest";
    private int maxVideoMinutes = 7;
    private int geminiMinIntervalSeconds = 6;
    // 페일오버 알림용 — 기존 봇 값을 recipe 설정 그룹으로 복사 소유 (분리 규율 5·7). 비면 알림 생략.
    private String telegramToken = "";
    private String telegramChatId = "";
    // 로컬 모델 페일오버 (2026-07-14 확정): 앱은 도커 컨테이너(Alpine)라 yt-dlp·ffmpeg·whisper 를
    // 직접 실행할 수 없음 — mac-mini 호스트에서 상시 도는 파이썬 서비스(chs/server/gikka-local)를
    // host.docker.internal 로 호출한다 (LM Studio 와 동일한 host.docker.internal 패턴).
    private String localExtractorBaseUrl = "http://host.docker.internal:8765";
    private boolean localExtractorEnabled = true;
    // 재료 신고 → 전력 재분석 (2026-07-18 확정): 서로 다른 신고자 수가 임계값에 닿으면 재분석.
    // 지금은 오너 혼자라 1 — 공개 시 2→10 처럼 설정만 올려 노이즈를 거른다(코드 무수정, 사용자 확정).
    private int reportAnalyzeThreshold = 1;
    // 같은 (영상, 재료)의 재분석 실행 상한 — "신고→같은 결과→재신고" 무한 루프(Gemini 낭비) 차단.
    // 상한 도달 후의 신고는 기록만 남는다("재분석으로 못 고치는 부류"라는 관찰 데이터가 됨).
    private int reportMaxRuns = 2;

    public String getGeminiApiKey() {
        return geminiApiKey;
    }

    public void setGeminiApiKey(String geminiApiKey) {
        this.geminiApiKey = geminiApiKey;
    }

    public String getYoutubeApiKey() {
        return youtubeApiKey;
    }

    public void setYoutubeApiKey(String youtubeApiKey) {
        this.youtubeApiKey = youtubeApiKey;
    }

    public String getGeminiModel() {
        return geminiModel;
    }

    public void setGeminiModel(String geminiModel) {
        this.geminiModel = geminiModel;
    }

    public int getMaxVideoMinutes() {
        return maxVideoMinutes;
    }

    public void setMaxVideoMinutes(int maxVideoMinutes) {
        this.maxVideoMinutes = maxVideoMinutes;
    }

    public String getGeminiFallbackModel() {
        return geminiFallbackModel;
    }

    public void setGeminiFallbackModel(String geminiFallbackModel) {
        this.geminiFallbackModel = geminiFallbackModel;
    }

    public String getTelegramToken() {
        return telegramToken;
    }

    public void setTelegramToken(String telegramToken) {
        this.telegramToken = telegramToken;
    }

    public String getTelegramChatId() {
        return telegramChatId;
    }

    public void setTelegramChatId(String telegramChatId) {
        this.telegramChatId = telegramChatId;
    }

    public int getGeminiMinIntervalSeconds() {
        return geminiMinIntervalSeconds;
    }

    public void setGeminiMinIntervalSeconds(int geminiMinIntervalSeconds) {
        this.geminiMinIntervalSeconds = geminiMinIntervalSeconds;
    }

    public String getLocalExtractorBaseUrl() {
        return localExtractorBaseUrl;
    }

    public void setLocalExtractorBaseUrl(String localExtractorBaseUrl) {
        this.localExtractorBaseUrl = localExtractorBaseUrl;
    }

    public boolean isLocalExtractorEnabled() {
        return localExtractorEnabled;
    }

    public void setLocalExtractorEnabled(boolean localExtractorEnabled) {
        this.localExtractorEnabled = localExtractorEnabled;
    }

    public int getReportAnalyzeThreshold() {
        return reportAnalyzeThreshold;
    }

    public void setReportAnalyzeThreshold(int reportAnalyzeThreshold) {
        this.reportAnalyzeThreshold = reportAnalyzeThreshold;
    }

    public int getReportMaxRuns() {
        return reportMaxRuns;
    }

    public void setReportMaxRuns(int reportMaxRuns) {
        this.reportMaxRuns = reportMaxRuns;
    }
}
