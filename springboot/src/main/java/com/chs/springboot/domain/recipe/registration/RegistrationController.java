// [AGENT] 등록·대기열 API — 경로 /api/recipe/** 통일 (분리 규율 3), 응답은 프론트 registrationTypes.ts 계약
// 인증: @GikkaUserId (리졸버가 미로그인 401 처리 — 본문에 인증 코드 없음)
// 에러 계약: 상태 코드만 (400=링크 인식 불가, 404=없는 항목, 409=중복) — 문구는 프론트 소유
// 2026-07-13 재편: video(분석 결과, video_id 당 1행) + registration(user_id<->video_id 연결) 분리.
// 영상이 이미 있으면 메타 조회 없이 연결만 추가 — CONTEXT.md 확정(같은 영상 중복 분석 방지).
package com.chs.springboot.domain.recipe.registration;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.chs.springboot.domain.recipe.auth.GikkaAuthProperties;
import com.chs.springboot.domain.recipe.auth.GikkaUserId;
import com.chs.springboot.domain.recipe.user.GikkaUserRepository;
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
    private final GeminiRateLimiter rateLimiter;
    private final VideoMetadataClient metadata;
    private final GikkaAuthProperties authProperties;
    private final GikkaUserRepository users;
    /** 오너 모니터의 로컬 추출기 상태 표시용 — Hybrid(@Primary)가 아니라 로컬 구현체를 직접 받는다.
        "로컬이 지금 쓸 수 있는 상태인가"는 라우팅과 무관한 로컬 자신의 사실이므로 (2026-07-16) */
    private final LocalRecipeExtractor localExtractor;
    private final ObjectMapper mapper = new ObjectMapper();

    public RegistrationController(RegistrationRepository repository, VideoRepository videos,
                                  GeminiRateLimiter rateLimiter, VideoMetadataClient metadata,
                                  GikkaAuthProperties authProperties, GikkaUserRepository users,
                                  LocalRecipeExtractor localExtractor) {
        this.localExtractor = localExtractor;
        this.repository = repository;
        this.videos = videos;
        this.rateLimiter = rateLimiter;
        this.metadata = metadata;
        this.authProperties = authProperties;
        this.users = users;
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

    /** 영상 1개 등록 — 이미 있는 영상이면 메타 조회 없이 연결만(길이 컷 판정도 이미 끝난 상태) */
    @PostMapping
    public RegistrationResponse register(@GikkaUserId long userId, @RequestBody RegisterRequest request) {
        String videoId = YoutubeVideoId.parseVideoId(nonNullUrl(request))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "유튜브 링크 인식 불가"));
        if (repository.exists(userId, videoId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 등록된 영상");
        }
        Optional<VideoMetadataClient.VideoMetadata> meta =
                videos.existsActive(videoId) ? Optional.empty() : metadata.fetchOne(videoId);
        repository.registerLink(userId, videoId, meta);
        return repository.find(userId, videoId).map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "등록 직후 조회 실패"));
    }

    /** 재생목록 일괄 등록 — 이미 있던 영상은 건너뛰고 추가된 수를 반환. 신규 영상만 메타 조회 */
    @PostMapping("/playlist")
    public Map<String, Integer> registerPlaylist(@GikkaUserId long userId, @RequestBody RegisterRequest request) {
        String playlistId = YoutubeVideoId.parsePlaylistId(nonNullUrl(request))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "재생목록 링크 인식 불가"));
        List<String> videoIds = metadata.playlistVideoIds(playlistId);
        List<String> unknownVideoIds = videoIds.stream().filter(id -> !videos.existsActive(id)).toList();
        Map<String, VideoMetadataClient.VideoMetadata> metaById =
                RegistrationRules.metadataById(metadata.fetch(unknownVideoIds));
        int added = 0;
        for (String videoId : videoIds) {
            if (repository.exists(userId, videoId)) {
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
    // 동작은 모니터링 화면(/monitor/{videoId}/reanalyze, 아래) 한 곳에 모으는 원칙으로 통일).

    /** limit 필수 — 표시 개수의 원본은 프론트 상수 하나뿐 (서버 기본값 이중 정의 금지) */
    @GetMapping("/recent")
    public List<RegistrationResponse> recentDone(@GikkaUserId long userId, @RequestParam int limit) {
        return repository.recentDone(userId, limit).stream().map(this::toResponse).toList();
    }

    /** 프론트 MonitorItem 과 1:1 (검증단계 실시간 모니터링 — 2026-07-13 확정).
        geminiRetryCount: 로컬 대체 결과가 없어 Gemini 재시도 루프를 도는 중인 횟수
        (2026-07-14 확정 — attempt_count 는 이 상황에서 소모되지 않게 설계돼 있어 별도 노출) */
    public record MonitorResponse(long userId, String email, String videoId, String url, String title,
                                  String category, String status, Integer durationSeconds, int attemptCount,
                                  String lastError, Integer analysisSeconds,
                                  String registeredAt, String analyzingStartedAt, int geminiRetryCount) {
    }

    /** 프론트 MonitorSnapshot 과 1:1 — 대기열 크기·워커 생존·429 이력 + 항목 목록 한 번에 (2026-07-13 확정).
        nextRetryAt: Gemini 백오프 중이면 다음 재시도 가능 시각, 아니면 과거 시각 (2026-07-14 확정 —
        모니터링 화면의 카운트다운 표시용, gemini_rate.last_call_at 재사용) */
    /** localExtractor: 호스트 서비스가 스스로 보고한 사실 그대로(JSON 통과). 서비스가 죽었거나
        /health 가 없는 옛 버전이면 null — 그 null 자체가 "지금 로컬을 못 쓴다"는 사실이다.
        판정·문구는 프론트가 한다 (pattern-raw-signal) */
    public record MonitorSnapshot(int waitingCount, int analyzingCount, String workerHeartbeatAt,
                                  int rateLimitCount, String lastRateLimitedAt, String nextRetryAt,
                                  com.fasterxml.jackson.databind.JsonNode localExtractor,
                                  List<MonitorResponse> items) {
    }

    /** 전 사용자 대기열 실시간 조회 — 오너 전용 (허용 목록 재사용, 목록 비면 아무도 접근 불가) */
    @GetMapping("/monitor")
    public MonitorSnapshot monitor(@GikkaUserId long userId, @RequestParam int limit) {
        requireOwner(userId);
        VideoRepository.QueueCounts counts = videos.queueCounts();
        GeminiRateLimiter.WorkerStatus worker = rateLimiter.workerStatus();
        List<MonitorResponse> items = repository.listForMonitor(limit).stream()
                .map(row -> new MonitorResponse(
                        row.userId(), row.email(), row.videoId(), row.url(), row.title(),
                        row.category(), row.status(), row.durationSeconds(), row.attemptCount(),
                        row.lastError(), row.analysisSeconds(), row.registeredAt().toString(),
                        row.analyzingStartedAt() == null ? null : row.analyzingStartedAt().toString(),
                        row.geminiRetryCount()))
                .toList();
        return new MonitorSnapshot(
                counts.waiting(), counts.analyzing(),
                worker.heartbeatAt() == null ? null : worker.heartbeatAt().toString(),
                worker.rateLimitCount(),
                worker.lastRateLimitedAt() == null ? null : worker.lastRateLimitedAt().toString(),
                worker.nextRetryAt() == null ? null : worker.nextRetryAt().toString(),
                localExtractor.health(),
                items);
    }

    /** 모니터링 화면 전용 강제 재분석 — 오너 전용, 요청자 본인이 등록했는지 여부와 무관 (2026-07-13 확정).
        메타도 함께 재조회 (일반 재분석과 동일 이유) */
    @PostMapping("/monitor/{videoId}/reanalyze")
    public void monitorReanalyze(@GikkaUserId long userId, @PathVariable String videoId) {
        requireOwner(userId);
        if (!videos.reanalyze(videoId, metadata.fetchOne(videoId))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "없는 영상");
        }
    }

    /**
     * 영상 삭제 — 오너 전용 (2026-07-13 확정, 원본이 유튜브에서 삭제·비공개된 경우 등).
     * 영상정보는 남기고 분석정보만 지워 REMOVED 로 표시 — 등록했던 다른 사용자 목록에도
     * "삭제됨"으로 보임(연결은 유지). 재분석하면 평소 파이프라인을 다시 타며 자동 복구됨.
     */
    @PostMapping("/monitor/{videoId}/remove")
    public void monitorRemove(@GikkaUserId long userId, @PathVariable String videoId) {
        requireOwner(userId);
        if (!videos.remove(videoId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "없는 영상");
        }
    }

    private void requireOwner(long userId) {
        if (!authProperties.isOwner(users.findEmail(userId))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "오너 전용 기능");
        }
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
                row.registeredAt().toString(),
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
