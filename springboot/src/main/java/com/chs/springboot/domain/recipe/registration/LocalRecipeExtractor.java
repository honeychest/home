// [AGENT] 로컬 모델 페일오버 구현체 (2026-07-14 확정) — mac-mini 호스트에서 상시 도는
// 파이썬 서비스(chs/server/gikka-local, yt-dlp→ffmpeg→whisper turbo→LM Studio)를
// host.docker.internal 로 호출한다. 앱이 도커 컨테이너(Alpine)라 yt-dlp·ffmpeg·whisper 를
// 직접 실행할 수 없어 프로세스 실행이 아니라 네트워크 호출로 격리(LM Studio 와 동일 패턴).
// 외부 HTTP 는 RestClient.Builder 주입 (PLAYBOOK 관례 4, GeminiRecipeExtractor 와 동일 시임).
package com.chs.springboot.domain.recipe.registration;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class LocalRecipeExtractor implements RecipeExtractor {

    private final RestClient rest;
    private final GikkaMediaProperties properties;

    public LocalRecipeExtractor(RestClient.Builder builder, GikkaMediaProperties properties) {
        this.rest = builder.baseUrl(properties.getLocalExtractorBaseUrl()).build();
        this.properties = properties;
    }

    /** 서비스 미기동 등 로컬 파이프라인 자체가 안 되는 상황 — Hybrid 가 Gemini 로 넘어가야 함 */
    public static class LocalUnavailableException extends RuntimeException {
        public LocalUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @Override
    public ExtractionResult extract(String videoUrl, String description) {
        if (!properties.isLocalExtractorEnabled()) {
            throw new LocalUnavailableException("로컬 추출기 비활성화 설정", null);
        }
        Map<String, Object> body = description == null
                ? Map.of("videoUrl", videoUrl)
                : Map.of("videoUrl", videoUrl, "description", description);
        try {
            JsonNode response = rest.post()
                    .uri("/extract")
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            // 호스트 서비스는 ExtractionResult 와 1:1 JSON 을 그대로 돌려줌 (봉투 없음) —
            // transcriptChars(whisper 전사 글자 수)는 로컬만 아는 사실이라 여기서만 실려 온다
            return ExtractionResultJson.parse(response);
        } catch (Exception e) {
            // 네트워크 오류·서비스 다운·타임아웃 전부 "로컬 불가" 로 묶는다 — Hybrid 가 Gemini 로 폴백
            throw new LocalUnavailableException("로컬 추출 서비스 호출 실패: " + e.getMessage(), e);
        }
    }
}
