// [AGENT] 보관함 API — 경로 /api/recipe/** 통일 (분리 규율 3), 응답은 프론트 registrationTypes.ts 계약
// 인증: @GikkaUserId (리졸버가 미로그인 401 처리 — 본문에 인증 코드 없음)
// 에러 계약: 상태 코드만 (400=링크 인식 불가, 404=없는 항목, 409=중복) — 문구는 프론트 소유
// 2026-07-13 재편: video(분석 결과, video_id 당 1행) + registration(user_id<->video_id 연결) 분리.
// 영상이 이미 있으면 메타 조회 없이 연결만 추가 — CONTEXT.md 확정(같은 영상 중복 분석 방지).
//
// 2026-07-25 분할: 449줄·협력자 9개에 보관함·모니터·사전 세 청중이 섞여 있었다. 이제 여기는
// **일반 사용자의 보관함**만 담당한다 — 운영자 대기열은 MonitorController, 재료 사전은
// dictionary/DictionaryController (경로는 셋 다 그대로).
package com.chs.gikka.registration;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.chs.gikka.auth.GikkaOwnerGuard;
import com.chs.gikka.auth.GikkaUserId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final VideoRepository videos;
    private final VideoMetadataClient metadata;
    private final GikkaOwnerGuard owner;
    private final ObjectMapper mapper = new ObjectMapper();

    public RegistrationController(RegistrationRepository repository, VideoRepository videos,
                                  VideoMetadataClient metadata, GikkaOwnerGuard owner) {
        this.repository = repository;
        this.videos = videos;
        this.metadata = metadata;
        this.owner = owner;
    }

    public record RegisterRequest(String url) {
    }

    /** 프론트 RegistrationItem 과 1:1 (summary=TIP/ETC 요약, tags=검색 태그 — 2026-07-13 확정).
        analysisSignals: 이 분석에 실제로 쓸 수 있었던 원시 신호 목록(예: ["FRAMES","DESCRIPTION"] —
        TRANSCRIPT 가 빠졌으면 음성 인식이 거의 안 됐다는 뜻) — 경고 문구는 프론트가 도출
        (2026-07-14 확정, pattern-raw-signal) */
    public record RegistrationResponse(String videoId, String url, String platform, String category,
                                       String status, String title, String thumbnailUrl,
                                       Integer durationSeconds, JsonNode recipe,
                                       String summary, JsonNode tags, String registeredAt,
                                       JsonNode analysisSignals) {
    }

    @GetMapping
    public List<RegistrationResponse> list(@GikkaUserId long userId) {
        return repository.list(userId).stream().map(this::toResponse).toList();
    }

    /** 보관함 검색 — 내 보관함만 (2026-07-16 5차 도입, 2026-07-25 gikka 전체 보완 폐지 — 남의
        데이터가 섞이면 개인 데이터 관리가 안 되는 느낌을 주고, 추천 탭이 이미 gikka 전체를
        보여주고 있어 중복이라는 사용자 판단). 매칭은 제목·요리 이름·태그 부분일치. q 가 비면 빈 결과. */
    @GetMapping("/search")
    public List<RegistrationResponse> search(@GikkaUserId long userId, @RequestParam String q) {
        String query = q.trim();
        if (query.isEmpty()) {
            return List.of();
        }
        return repository.searchMine(userId, query).stream().map(this::toResponse).toList();
    }

    /** 추천에서 고른 남의 영상을 내 보관함에 담기 (2026-07-16 5차) — URL 재입력·재분석 없이
        video_id 로 내 registration 만 새로 만든다(이미 분석돼 있으므로). 없는·삭제된 영상=404,
        이미 내 것=409. 기존 "이미 있으면 연결만" 경로(registerLink)와 같은 성격. */
    @PostMapping("/by-video/{videoId}")
    public RegistrationResponse registerByVideoId(@GikkaUserId long userId, @PathVariable String videoId) {
        if (!videos.existsActive(videoId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "없는 영상");
        }
        if (repository.exists(userId, videoId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 등록된 영상");
        }
        repository.registerLink(userId, videoId, Optional.empty());
        return repository.find(userId, videoId).map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "등록 직후 조회 실패"));
    }

    /** 영상 1개 등록 — 이미 있는 영상이면 메타 조회 없이 연결만(길이 컷 판정도 이미 끝난 상태) */
    @PostMapping
    public RegistrationResponse register(@GikkaUserId long userId, @RequestBody RegisterRequest request) {
        String videoId = YoutubeVideoId.parseVideoId(nonNullUrl(request))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "유튜브 링크 인식 불가"));
        if (repository.exists(userId, videoId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 등록된 영상");
        }
        // 이미 video 테이블에 있는(다른 사용자가 등록했던) 영상은 재조회 없이 연결만 — 그때 이미 검증됨.
        // 신규 영상만 메타를 조회하고, 없으면(㉠ 비공개·삭제·잘못된 ID) 등록을 막는다.
        // 조회 자체가 실패하면(㉡ 순간 장애) 멀쩡한 영상을 영구 거부하지 않도록 메타 없이 등록 허용.
        Optional<VideoMetadataClient.VideoMetadata> meta;
        if (videos.existsActive(videoId)) {
            meta = Optional.empty();
        } else {
            try {
                meta = Optional.of(metadata.fetchOne(videoId).orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND,
                                "비공개·삭제된 영상이거나 잘못된 링크")));
            } catch (VideoMetadataClient.TransientMetadataException e) {
                meta = Optional.empty(); // ㉡ — 등록은 허용, 워커가 분석 시도
            }
        }
        repository.registerLink(userId, videoId, meta);
        return repository.find(userId, videoId).map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "등록 직후 조회 실패"));
    }

    /** 비-오너의 재생목록 일괄 등록 상한 (2026-07-25 확정) — 한 번 요청으로 대기열에 대량으로
        밀어넣는 걸 막는 목적. 링크를 하나씩 붙여넣는 일반 등록은 사람이 직접 붙여넣는 속도가
        자연스러운 제한이 돼 별도 장치를 안 둔다 — 재생목록만 한 번의 클릭으로 대량 등록이 가능해
        여기만 상한을 둔다. 오너는 검증·시딩 목적이 있어 예외. */
    private static final int PLAYLIST_LIMIT_FOR_NON_OWNER = 10;

    /** 재생목록 일괄 등록 — 이미 있던 영상은 건너뛰고 추가된 수를 반환. 신규 영상만 메타 조회 */
    @PostMapping("/playlist")
    public Map<String, Integer> registerPlaylist(@GikkaUserId long userId, @RequestBody RegisterRequest request) {
        String playlistId = YoutubeVideoId.parsePlaylistId(nonNullUrl(request))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "재생목록 링크 인식 불가"));
        List<String> videoIds = metadata.playlistVideoIds(playlistId);
        // 오너 판정은 "막기"가 아니라 "상한 면제" 분기라 require 가 아니라 isOwner 를 쓴다
        if (!owner.isOwner(userId) && videoIds.size() > PLAYLIST_LIMIT_FOR_NON_OWNER) {
            videoIds = videoIds.subList(0, PLAYLIST_LIMIT_FOR_NON_OWNER);
        }
        List<String> unknownVideoIds = videoIds.stream().filter(id -> !videos.existsActive(id)).toList();
        Map<String, VideoMetadataClient.VideoMetadata> metaById;
        try {
            metaById = RegistrationRules.metadataById(metadata.fetch(unknownVideoIds));
        } catch (VideoMetadataClient.TransientMetadataException e) {
            // ㉡ 조회 호출 실패 — 메타 없이 강행하면 그 사이 비공개된 영상까지 조용히 등록된다.
            // 일괄 등록은 재실행이 안전(이미 등록된 건 건너뜀)하니 깔끔히 실패시키고 재시도하게 한다.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "잠시 후 다시 시도해 주세요");
        }
        int added = 0;
        for (String videoId : videoIds) {
            if (repository.exists(userId, videoId)) {
                continue;
            }
            // 신규인데 메타가 없으면 = 비공개·삭제된 영상(㉠) — 조용히 건너뛴다 (added 에 안 셈).
            // 이미 video 에 있는 영상은 메타 없이도 연결 (register 와 같은 규칙).
            if (metaById.get(videoId) == null && !videos.existsActive(videoId)) {
                continue;
            }
            boolean linked = repository.registerLink(userId, videoId,
                    Optional.ofNullable(metaById.get(videoId)));
            if (linked) {
                added++;
            }
        }
        return Map.of("added", added);
    }

    /**
     * 내 목록에서 지우기 — 내 registration 연결만 삭제 (2026-07-14 확정, 오너 전용 "영상 삭제"와는
     * 다른 기능). video·다른 사용자 연결은 그대로 유지 — 계속 관리(추가·정리)가 가능해야 한다는 요구.
     */
    @DeleteMapping("/{videoId}")
    public void unregister(@GikkaUserId long userId, @PathVariable String videoId) {
        if (!repository.exists(userId, videoId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "등록된 영상이 아님");
        }
        repository.delete(userId, videoId);
    }

    // 일반 사용자용 재분석 엔드포인트는 폐지 (2026-07-14 확정 — 오판 구제를 포함한 모든 강제
    // 동작은 운영자 모드(MonitorController) 한 곳에 모으는 원칙으로 통일).

    /** limit 필수 — 표시 개수의 원본은 프론트 상수 하나뿐 (서버 기본값 이중 정의 금지) */
    @GetMapping("/recent")
    public List<RegistrationResponse> recentDone(@GikkaUserId long userId, @RequestParam int limit) {
        return repository.recentDone(userId, limit).stream().map(this::toResponse).toList();
    }

    private static String nonNullUrl(RegisterRequest request) {
        if (request == null || request.url() == null || request.url().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "url 누락");
        }
        return request.url();
    }

    private RegistrationResponse toResponse(RegistrationRepository.Row row) {
        return new RegistrationResponse(
                row.videoId(), row.url(), row.platform(), row.category(), row.status(),
                row.title(), row.thumbnailUrl(), row.durationSeconds(),
                readJson(row.recipeJson(), "recipe_json", row.videoId()),
                row.summary(),
                readJson(row.tagsJson(), "tags", row.videoId()),
                row.registeredAt() == null ? null : row.registeredAt().toString(),
                readJson(row.analysisSignalsJson(), "analysis_signals", row.videoId()));
    }

    private JsonNode readJson(String json, String column, String videoId) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(column + " 파싱 실패: video=" + videoId, e);
        }
    }
}
