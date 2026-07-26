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
    private final GikkaYoutubeProperties properties;

    public YoutubeMetadataClient(RestClient.Builder builder, GikkaYoutubeProperties properties) {
        this.rest = builder.baseUrl("https://www.googleapis.com/youtube/v3").build();
        this.properties = properties;
    }

    @Override
    public List<VideoMetadata> fetch(List<String> videoIds) {
        if (properties.getApiKey().isBlank() || videoIds.isEmpty()) {
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
                                .queryParam("key", properties.getApiKey())
                                .build())
                        .retrieve()
                        .body(JsonNode.class);
                for (JsonNode item : body.path("items")) {
                    result.add(toMetadata(item));
                }
                // 200 인데 특정 videoId 가 items 에 없으면 = 그 영상이 비공개·삭제·잘못된 ID (㉠).
                // 그건 예외가 아니라 "없음"이라는 정상 응답이라 여기서 아무것도 안 한다 —
                // 결과 목록에서 빠지는 것 자체가 신호다 (호출자가 등록 차단으로 처리).
            } catch (Exception e) {
                // 조회 호출 자체가 실패(㉡ — HTTP 오류·네트워크·타임아웃). 이건 "영상이 없다"가
                // 아니라 "지금 못 물어봤다"라 삼키면 안 된다: 삼키면 멀쩡한 영상이 "없음"으로 둔갑해
                // 부당하게 차단된다 (2026-07-16 — 이전엔 삼키고 등록을 강행해 반대 문제였음).
                log.warn("[gikka] YouTube 메타 조회 호출 실패 (일시적): {}", e.getMessage());
                throw new TransientMetadataException(e.getMessage());
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
                                .queryParam("key", properties.getApiKey());
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
                parseIsoDurationSeconds(item.path("contentDetails").path("duration").asText(null)),
                snippet.path("description").asText(null));
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
