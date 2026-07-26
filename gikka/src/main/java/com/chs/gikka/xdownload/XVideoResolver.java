// [AGENT] X(트위터) 영상 다운로드 — 링크 추출 전용 (2026-07-20 확정)
// recipe 의 분석 파이프라인(LocalRecipeExtractor)과 달리 서버는 영상 바이트를 절대 만지지 않는다.
// gikka 호스트 서비스(mac-mini, yt-dlp)에 메타데이터 조회만 위임해 twimg.com 직접 주소를
// 받아오고, 실제 다운로드는 폰 브라우저가 그 주소로 직접 한다 (pattern-rest-seam,
// LocalRecipeExtractor 와 동일한 RestClient.Builder 시임).
package com.chs.gikka.xdownload;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.chs.gikka.external.GikkaHostServiceProperties;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class XVideoResolver {

    private final RestClient rest;

    public XVideoResolver(RestClient.Builder builder, GikkaHostServiceProperties properties) {
        this.rest = builder.baseUrl(properties.getBaseUrl()).build();
    }

    /** 호스트 서비스 호출 실패(네트워크·비공개 게시물·지원 안 되는 형식 등) — 전부 하나로 묶는다 */
    public static class ResolveFailedException extends RuntimeException {
        public ResolveFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public record VideoOption(int height, String url) {
    }

    /** 게시물 안 영상 하나 — 영상이 여러 개인 게시물은 이게 여러 개 온다 (2026-07-20 확정,
        3개짜리 게시물 다운로드 실패 제보로 응답을 "영상 1개" 에서 "영상 목록"으로 바꿈) */
    public record VideoItem(String title, String thumbnail, List<VideoOption> options) {
    }

    public record ResolveResult(List<VideoItem> items) {
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
        List<VideoItem> items = new ArrayList<>();
        for (JsonNode itemNode : node.path("items")) {
            List<VideoOption> options = new ArrayList<>();
            for (JsonNode o : itemNode.path("options")) {
                options.add(new VideoOption(o.path("height").asInt(), o.path("url").asText()));
            }
            items.add(new VideoItem(itemNode.path("title").asText(""), itemNode.path("thumbnail").asText(""), options));
        }
        return new ResolveResult(items);
    }
}
