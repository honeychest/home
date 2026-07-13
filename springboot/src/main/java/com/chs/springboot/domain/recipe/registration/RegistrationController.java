// [AGENT] 등록·대기열 API — 경로 /api/recipe/** 통일 (분리 규율 3), 응답은 프론트 registrationTypes.ts 계약
// 인증: @GikkaUserId (리졸버가 미로그인 401 처리 — 본문에 인증 코드 없음)
// 에러 계약: 상태 코드만 (400=링크 인식 불가, 404=없는 항목, 409=중복) — 문구는 프론트 소유
package com.chs.springboot.domain.recipe.registration;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.chs.springboot.domain.recipe.auth.GikkaUserId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/recipe/registrations")
public class RegistrationController {

    private final RegistrationRepository repository;
    private final VideoMetadataClient metadata;
    private final GikkaMediaProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();

    public RegistrationController(RegistrationRepository repository, VideoMetadataClient metadata,
                                  GikkaMediaProperties properties) {
        this.repository = repository;
        this.metadata = metadata;
        this.properties = properties;
    }

    public record RegisterRequest(String url) {
    }

    /** 프론트 RegistrationItem 과 1:1 */
    public record RegistrationResponse(String videoId, String url, String platform, String category,
                                       String status, String title, String thumbnailUrl,
                                       Integer durationSeconds, JsonNode recipe, String registeredAt) {
    }

    @GetMapping
    public List<RegistrationResponse> list(@GikkaUserId long userId) {
        return repository.list(userId).stream().map(this::toResponse).toList();
    }

    /** 영상 1개 등록 — 메타 조회로 길이 컷을 등록 시점에 판정 (TOO_LONG 은 즉시 응답에 실림) */
    @PostMapping
    public RegistrationResponse register(@GikkaUserId long userId, @RequestBody RegisterRequest request) {
        String videoId = YoutubeVideoId.parseVideoId(nonNullUrl(request))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "유튜브 링크 인식 불가"));
        if (repository.exists(userId, videoId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 등록된 영상");
        }
        insertWithMeta(userId, videoId, metadata.fetchOne(videoId));
        return repository.find(userId, videoId).map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "등록 직후 조회 실패"));
    }

    /** 재생목록 일괄 등록 — 이미 있던 영상은 건너뛰고 추가된 수를 반환 */
    @PostMapping("/playlist")
    public Map<String, Integer> registerPlaylist(@GikkaUserId long userId, @RequestBody RegisterRequest request) {
        String playlistId = YoutubeVideoId.parsePlaylistId(nonNullUrl(request))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "재생목록 링크 인식 불가"));
        List<String> videoIds = metadata.playlistVideoIds(playlistId);
        Map<String, VideoMetadataClient.VideoMetadata> metaById =
                RegistrationRules.metadataById(metadata.fetch(videoIds));
        int added = 0;
        for (String videoId : videoIds) {
            if (repository.exists(userId, videoId)) {
                continue;
            }
            if (insertWithMeta(userId, videoId, Optional.ofNullable(metaById.get(videoId)))) {
                added++;
            }
        }
        return Map.of("added", added);
    }

    @PostMapping("/{videoId}/reanalyze")
    public void reanalyze(@GikkaUserId long userId, @PathVariable String videoId) {
        if (!repository.reanalyze(userId, videoId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "등록된 영상이 아님");
        }
    }

    /** limit 필수 — 표시 개수의 원본은 프론트 상수 하나뿐 (서버 기본값 이중 정의 금지) */
    @GetMapping("/recent")
    public List<RegistrationResponse> recentDone(@GikkaUserId long userId, @RequestParam int limit) {
        return repository.recentDone(userId, limit).stream().map(this::toResponse).toList();
    }

    private boolean insertWithMeta(long userId, String videoId,
                                   Optional<VideoMetadataClient.VideoMetadata> meta) {
        String status = RegistrationRules.initialStatus(
                meta.map(VideoMetadataClient.VideoMetadata::durationSeconds).orElse(null),
                properties.getMaxVideoMinutes());
        return repository.insert(
                userId, videoId,
                "https://www.youtube.com/watch?v=" + videoId,
                status,
                meta.map(VideoMetadataClient.VideoMetadata::title).orElse(null),
                meta.map(VideoMetadataClient.VideoMetadata::thumbnailUrl).orElse(null),
                meta.map(VideoMetadataClient.VideoMetadata::durationSeconds).orElse(null));
    }

    private static String nonNullUrl(RegisterRequest request) {
        if (request == null || request.url() == null || request.url().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "url 누락");
        }
        return request.url();
    }

    private RegistrationResponse toResponse(RegistrationRepository.Row row) {
        JsonNode recipe = null;
        if (row.recipeJson() != null) {
            try {
                recipe = mapper.readTree(row.recipeJson());
            } catch (Exception e) {
                throw new IllegalStateException("recipe_json 파싱 실패: video=" + row.videoId(), e);
            }
        }
        return new RegistrationResponse(
                row.videoId(), row.url(), row.platform(), row.category(), row.status(),
                row.title(), row.thumbnailUrl(), row.durationSeconds(), recipe,
                row.registeredAt().toString());
    }
}
