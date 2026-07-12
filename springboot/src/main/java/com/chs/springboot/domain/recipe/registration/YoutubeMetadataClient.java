// [AGENT] YouTube Data API 메타 조회 — 제목·섬네일·길이 (분석 전 확보, 7분 컷의 근거)
// 외부 HTTP 는 RestClient.Builder 주입 (PLAYBOOK 관례 4 — MockRestServiceServer 테스트 시임).
// 무료 할당량: 일 10,000유닛, videos.list/playlistItems.list = 1유닛 — 개인 규모에 충분.
package com.chs.springboot.domain.recipe.registration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class YoutubeMetadataClient implements VideoMetadataClient {

    private static final Logger log = LoggerFactory.getLogger(YoutubeMetadataClient.class);
    private static final int PAGE_SIZE = 50;           // API 최대
    private static final int PLAYLIST_MAX = 500;       // 폭주 방지 상한

    private final RestClient rest;
    private final GikkaMediaProperties properties;

    public YoutubeMetadataClient(RestClient.Builder builder, GikkaMediaProperties properties) {
        this.rest = builder.baseUrl("https://www.googleapis.com/youtube/v3").build();
        this.properties = properties;
    }

    @Override
    public List<VideoMetadata> fetch(List<String> videoIds) {
        if (properties.getYoutubeApiKey().isBlank() || videoIds.isEmpty()) {
            return List.of();
        }
        List<VideoMetadata> result = new ArrayList<>();
        for (int from = 0; from < videoIds.size(); from += PAGE_SIZE) {
            List<String> page = videoIds.subList(from, Math.min(from + PAGE_SIZE, videoIds.size()));
            try {
                JsonNode body = rest.get()
                        .uri(uri -> uri.path("/videos")
                                .queryParam("part", "snippet,contentDetails")
                                .queryParam("id", String.join(",", page))
                                .queryParam("key", properties.getYoutubeApiKey())
                                .build())
                        .retrieve()
                        .body(JsonNode.class);
                for (JsonNode item : body.path("items")) {
                    result.add(toMetadata(item));
                }
            } catch (Exception e) {
                // 메타 조회 실패가 등록을 막으면 안 됨 (제목·길이 없이 등록 → 워커가 분석은 시도)
                log.warn("[gikka] YouTube 메타 조회 실패 (등록은 계속): {}", e.getMessage());
            }
        }
        return result;
    }

    @Override
    public List<String> playlistVideoIds(String playlistId) {
        List<String> ids = new ArrayList<>();
        String pageToken = null;
        do {
            String token = pageToken;
            JsonNode body = rest.get()
                    .uri(uri -> {
                        var b = uri.path("/playlistItems")
                                .queryParam("part", "contentDetails")
                                .queryParam("playlistId", playlistId)
                                .queryParam("maxResults", PAGE_SIZE)
                                .queryParam("key", properties.getYoutubeApiKey());
                        if (token != null) {
                            b.queryParam("pageToken", token);
                        }
                        return b.build();
                    })
                    .retrieve()
                    .body(JsonNode.class);
            for (JsonNode item : body.path("items")) {
                String videoId = item.path("contentDetails").path("videoId").asText(null);
                if (videoId != null) {
                    ids.add(videoId);
                }
            }
            pageToken = body.path("nextPageToken").asText(null);
        } while (pageToken != null && ids.size() < PLAYLIST_MAX);
        return ids;
    }

    private static VideoMetadata toMetadata(JsonNode item) {
        JsonNode snippet = item.path("snippet");
        JsonNode thumbs = snippet.path("thumbnails");
        // 세로 쇼츠에도 무난한 중간 해상도 우선
        String thumb = thumbs.path("medium").path("url").asText(
                thumbs.path("high").path("url").asText(
                        thumbs.path("default").path("url").asText(null)));
        return new VideoMetadata(
                item.path("id").asText(),
                snippet.path("title").asText(null),
                thumb,
                parseIsoDurationSeconds(item.path("contentDetails").path("duration").asText(null)));
    }

    /** ISO-8601 (PT1M30S 등) → 초. 파싱 불가면 null (길이 미상 = 컷하지 않고 분석 시도) */
    static Integer parseIsoDurationSeconds(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return (int) Duration.parse(iso).toSeconds();
        } catch (Exception e) {
            return null;
        }
    }
}
