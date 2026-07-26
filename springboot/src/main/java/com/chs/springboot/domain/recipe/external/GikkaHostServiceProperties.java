// [AGENT] mac-mini 호스트 서비스 설정 seam — 분리 규율 5 (recipe 전용 설정 그룹)
package com.chs.springboot.domain.recipe.external;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * gikka.host-service.* — mac-mini 호스트에서 상시 도는 파이썬 서비스(gikka-extractor/server.py)
 * 접속 정보. (2026-07-26 GikkaMediaProperties 분할로 신설)
 *
 * <p>앱은 도커 컨테이너(Alpine/aarch64)라 yt-dlp·ffmpeg·whisper 를 직접 실행할 수 없다 →
 * 호스트의 상시 HTTP 서비스를 host.docker.internal 로 부른다 (LM Studio 와 동일 패턴).
 * 배포·설정은 {@code gikka-extractor/README.md}.
 *
 * <p><b>왜 LLM 설정과 갈랐나</b> (2026-07-26): 이 주소를 쓰는 셋 중 하나는 LLM 이 아니다 —
 * 영상 추출(LocalRecipeExtractor)·재료 판정(LocalIngredientAuditor)은 LM Studio 를 타지만,
 * X 영상 해석(xdownload/XVideoResolver)은 yt-dlp 만 쓴다. 한 서비스가 두 성격의 일을 하므로
 * "그 서비스가 어디 있나"는 LLM 설정이 아니라 독립된 사실이다.
 *
 * <p>enabled=false 면 로컬 경로를 아예 안 탄다 — 부르던 쪽은 {@link LocalUnavailableException}
 * 을 받아 다른 채널로 넘어간다.
 */
@ConfigurationProperties(prefix = "gikka.host-service")
public class GikkaHostServiceProperties {

    private String baseUrl = "http://host.docker.internal:8765";
    private boolean enabled = true;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
