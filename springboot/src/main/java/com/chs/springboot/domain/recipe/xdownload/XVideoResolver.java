// [AGENT] X(트위터) 영상 다운로드 — 링크 추출 전용 (2026-07-20 확정)
// recipe 의 분석 파이프라인(LocalRecipeExtractor)과 달리 서버는 영상 바이트를 절대 만지지 않는다.
// gikka 호스트 서비스(mac-mini, yt-dlp)에 메타데이터 조회만 위임해 twimg.com 직접 주소를
// 받아오고, 실제 다운로드는 폰 브라우저가 그 주소로 직접 한다 (pattern-rest-seam,
// LocalRecipeExtractor 와 동일한 RestClient.Builder 시임).
package com.chs.springboot.domain.recipe.xdownload;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.chs.springboot.domain.recipe.registration.GikkaMediaProperties;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class XVideoResolver {

    private final RestClient rest;

    public XVideoResolver(RestClient.Builder builder, GikkaMediaProperties properties) {
        this.rest = builder.baseUrl(properties.getLocalExtractorBaseUrl()).build();
    }

    /** 호스트 서비스 호출 실패(네트워크·비공개 게시물·지원 안 되는 형식 등) — 전부 하나로 묶는다 */
    public static class ResolveFailedException extends RuntimeException {
        public ResolveFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public record VideoOption(int height, String url, boolean hasAudio) {
    }

    public record ResolveResult(String title, List<VideoOption> options) {
    }

    public ResolveResult resolve(String url) {
        try {
            JsonNode response = rest.post()
                    .uri("/x-resolve")
                    .body(Map.of("url", url))
                    .retrieve()
                    .body(JsonNode.class);
            return parse(response);
        } catch (Exception e) {
            throw new ResolveFailedException("영상 정보 조회 실패: " + e.getMessage(), e);
        }
    }

    private static ResolveResult parse(JsonNode node) {
        List<VideoOption> options = new ArrayList<>();
        for (JsonNode o : node.path("options")) {
            options.add(new VideoOption(o.path("height").asInt(), o.path("url").asText(),
                    o.path("hasAudio").asBoolean()));
        }
        return new ResolveResult(node.path("title").asText(""), options);
    }
}
