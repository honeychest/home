// [AGENT] 등록·대기열 API — 경로 /api/recipe/** 통일 (분리 규율 3), 응답은 프론트 registrationTypes.ts 계약
// 인증: @GikkaUserId (리졸버가 미로그인 401 처리 — 본문에 인증 코드 없음)
// 에러 계약: 상태 코드만 (400=링크 인식 불가, 404=없는 항목, 409=중복) — 문구는 프론트 소유
// 2026-07-13 재편: video(분석 결과, video_id 당 1행) + registration(user_id<->video_id 연결) 분리.
// 영상이 이미 있으면 메타 조회 없이 연결만 추가 — CONTEXT.md 확정(같은 영상 중복 분석 방지).
package com.chs.springboot.domain.recipe.registration;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final IngredientDictionaryRepository dictionary;
    private final IngredientChangeLogRepository changeLog;
    private final ObjectMapper mapper = new ObjectMapper();

    public RegistrationController(RegistrationRepository repository, VideoRepository videos,
                                  GeminiRateLimiter rateLimiter, VideoMetadataClient metadata,
                                  GikkaAuthProperties authProperties, GikkaUserRepository users,
                                  LocalRecipeExtractor localExtractor,
                                  IngredientDictionaryRepository dictionary,
                                  IngredientChangeLogRepository changeLog) {
        this.localExtractor = localExtractor;
        this.changeLog = changeLog;
        this.repository = repository;
        this.videos = videos;
        this.rateLimiter = rateLimiter;
        this.metadata = metadata;
        this.authProperties = authProperties;
        this.users = users;
        this.dictionary = dictionary;
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

    /** 보관함 검색 결과 (2026-07-16 5차) — 내 등록 우선(mine) + gikka 전체 보완(others).
        others 는 내가 등록 안 한 완료 영상이라 registeredAt=null 로 내려간다 (프론트 GikkaVideo). */
    public record SearchResponse(List<RegistrationResponse> mine, List<RegistrationResponse> others) {
    }

    /** 보관함 검색 — 내 보관함 우선, 결과가 부족해도 gikka 전체(등록 무관)를 항상 함께 보완 노출
        (2026-07-16 5차, 임계값 없이 항상 두 섹션 — CONTEXT.md "5차 확장" 2번). 매칭은 제목·요리
        이름·태그 부분일치. limit=others 개수 상한(프론트 상수). q 가 비면 빈 결과. */
    @GetMapping("/search")
    public SearchResponse search(@GikkaUserId long userId,
                                 @RequestParam String q, @RequestParam int limit) {
        String query = q.trim();
        if (query.isEmpty()) {
            return new SearchResponse(List.of(), List.of());
        }
        List<RegistrationResponse> mine = repository.searchMine(userId, query).stream()
                .map(this::toResponse).toList();
        List<RegistrationResponse> others = repository.searchOthers(userId, query, limit).stream()
                .map(this::toResponse).toList();
        return new SearchResponse(mine, others);
    }

    /** gikka 전체 보완에서 고른 영상을 내 보관함에 담기 (2026-07-16 5차) — URL 재입력·재분석 없이
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

    /** 재생목록 일괄 등록 — 이미 있던 영상은 건너뛰고 추가된 수를 반환. 신규 영상만 메타 조회 */
    @PostMapping("/playlist")
    public Map<String, Integer> registerPlaylist(@GikkaUserId long userId, @RequestBody RegisterRequest request) {
        String playlistId = YoutubeVideoId.parsePlaylistId(nonNullUrl(request))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "재생목록 링크 인식 불가"));
        List<String> videoIds = metadata.playlistVideoIds(playlistId);
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

    /** status 필터 허용값 — 잘못된 값은 400 (프론트 STATUS_LABEL 과 1:1) */
    private static final Set<String> MONITOR_STATUSES = Set.of(
            "WAITING", "ANALYZING", "DONE", "TOO_LONG", "FAILED", "REMOVED");

    /** 프론트 MonitorSnapshot 과 1:1 — 대기열 크기·워커 생존·429 이력 + 항목 목록 한 번에 (2026-07-13 확정).
        nextRetryAt: Gemini 백오프 중이면 다음 재시도 가능 시각, 아니면 과거 시각 (2026-07-14 확정 —
        모니터링 화면의 카운트다운 표시용, gemini_rate.last_call_at 재사용) */
    /** localExtractor: 호스트 서비스가 스스로 보고한 사실 그대로(JSON 통과). 서비스가 죽었거나
        /health 가 없는 옛 버전이면 null — 그 null 자체가 "지금 로컬을 못 쓴다"는 사실이다.
        판정·문구는 프론트가 한다 (pattern-raw-signal) */
    public record MonitorSnapshot(int waitingCount, int analyzingCount, String workerHeartbeatAt,
                                  int rateLimitCount, String lastRateLimitedAt, String nextRetryAt,
                                  com.fasterxml.jackson.databind.JsonNode localExtractor,
                                  Map<String, Integer> statusCounts,
                                  List<MonitorResponse> items) {
    }

    /** 전 사용자 대기열 실시간 조회 — 오너 전용 (허용 목록 재사용, 목록 비면 아무도 접근 불가).
        q(제목·요리명·태그 검색)·status 는 선택 필터 (2026-07-18 — 최신 limit 개 밖 영상 탐색용) */
    @GetMapping("/monitor")
    public MonitorSnapshot monitor(@GikkaUserId long userId, @RequestParam int limit,
                                   @RequestParam(required = false) String q,
                                   @RequestParam(required = false) String status) {
        requireOwner(userId);
        if (status != null && !MONITOR_STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "잘못된 status 필터");
        }
        VideoRepository.QueueCounts counts = videos.queueCounts();
        GeminiRateLimiter.WorkerStatus worker = rateLimiter.workerStatus();
        List<MonitorResponse> items = repository.listForMonitor(limit, q, status).stream()
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
                repository.monitorStatusCounts(),
                items);
    }

    /** 모니터 시트의 분석 내용 — 탭한 1건만 조회 (2026-07-18. 목록 폴링에 실으면 100행×재료·단계가
        2초마다 반복 전송돼 낭비 — 추천 슬림화와 같은 원칙). 프론트 MonitorAnalysis 와 1:1 */
    public record MonitorAnalysisResponse(String category, JsonNode recipe, String summary,
                                          JsonNode tags, JsonNode analysisSignals) {
    }

    @GetMapping("/monitor/{videoId}/analysis")
    public MonitorAnalysisResponse monitorAnalysis(@GikkaUserId long userId, @PathVariable String videoId) {
        requireOwner(userId);
        VideoRepository.Row row = videos.findById(videoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "없는 영상"));
        return new MonitorAnalysisResponse(
                row.category(),
                readJson(row.recipeJson(), "recipe_json", row.videoId()),
                row.summary(),
                readJson(row.tagsJson(), "tags", row.videoId()),
                readJson(row.analysisSignalsJson(), "analysis_signals", row.videoId()));
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

    /* ── 재료 사전 관리 (오너 전용) — 2026-07-17 5차-4 슬라이스1. classify() 가 읽는 MAIN/SEASONING
       분류의 단일 원본(ingredient_dictionary). 오너가 tier 를 직접 확정하거나 [AI 점검]으로 제안을
       받아 반영한다. "오너 전용 기능은 모니터 한 곳에 모은다"(2026-07-13) 정책에 따라 여기에 둔다. */

    @GetMapping("/dictionary")
    public List<IngredientDictionaryRepository.Entry> dictionary(@GikkaUserId long userId) {
        requireOwner(userId);
        return dictionary.all();
    }

    /** 냉장고 재료 추가 자동완성용 대표 이름 목록 (2026-07-19 확정) — 오너 아님, 로그인 사용자
        공용(사용자 오탈자 예방이 목적이라 모두에게 열려야 의미가 있음). 이름 목록만 노출 —
        status·그룹 등 관리 정보는 위 오너 전용 계약에만 있다. */
    @GetMapping("/dictionary/names")
    public List<String> dictionaryNames() {
        return dictionary.representativeNames();
    }

    /** 자동 반영 사후 감사용 (2026-07-18) — 파이프라인이 사전을 스스로 바꾼 최근 내역.
        개수 상한은 이 상수 하나가 원본 (사후 감사는 "최근 것 훑기"라 페이징 불필요). */
    private static final int CHANGE_LOG_LIMIT = 50;

    @GetMapping("/dictionary/changes")
    public List<IngredientChangeLogRepository.Entry> dictionaryChanges(@GikkaUserId long userId) {
        requireOwner(userId);
        return changeLog.recent(CHANGE_LOG_LIMIT);
    }

    public record ClassifyRequest(String name, String status) {
    }

    private static final Set<String> ALLOWED_STATUSES = Set.of(
            IngredientDictionaryRepository.STATUS_PENDING,
            IngredientDictionaryRepository.STATUS_SKIPPED,
            IngredientDictionaryRepository.STATUS_CONFIRMED_MAIN,
            IngredientDictionaryRepository.STATUS_CONFIRMED_SEASONING,
            IngredientDictionaryRepository.STATUS_CONFIRMED_BASIC);

    /** 오너 판정 — 이름의 status 를 정한다(tier 는 파생). 없는 이름=404, 잘못된 status=400 */
    @PostMapping("/dictionary/classify")
    public void classifyIngredient(@GikkaUserId long userId, @RequestBody ClassifyRequest request) {
        requireOwner(userId);
        if (request == null || request.name() == null || request.name().isBlank()
                || !ALLOWED_STATUSES.contains(request.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이름·상태 누락 또는 잘못된 상태");
        }
        if (!dictionary.updateStatus(request.name().trim(), request.status())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "사전에 없는 이름");
        }
    }

    /** 오너 일괄 판정 — [AI 점검] 제안 전체 적용용. 제안이 83개라(2026-07-17 실측) 한 건씩
        왕복하면 실질적으로 못 쓴다. 개별 classify 와 달리 없는 이름은 404 가 아니라 조용히
        건너뛴다 — 일괄이라 한 건 때문에 전체를 실패시키지 않는다.
        @return 실제로 바뀐 건수 (프론트는 안 쓰고 재조회로 화면을 맞춘다 — 확인·로그용) */
    @PostMapping("/dictionary/classify-batch")
    public int classifyIngredients(@GikkaUserId long userId, @RequestBody List<ClassifyRequest> requests) {
        requireOwner(userId);
        if (requests == null || requests.stream().anyMatch(r -> r == null || r.name() == null
                || r.name().isBlank() || !ALLOWED_STATUSES.contains(r.status()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이름·상태 누락 또는 잘못된 상태");
        }
        return dictionary.updateStatuses(requests.stream()
                .collect(Collectors.toMap(r -> r.name().trim(), ClassifyRequest::status, (a, b) -> b)));
    }

    /** 그룹 확정 요청 — name 을 matchKey 그룹에 넣는다. matchKey == name 이면 그룹 해제. */
    public record MergeRequest(String name, String matchKey) {
    }

    private static boolean isBlankMerge(MergeRequest r) {
        return r == null || r.name() == null || r.name().isBlank()
                || r.matchKey() == null || r.matchKey().isBlank();
    }

    /**
     * 오너의 그룹 확정 (2026-07-17 슬라이스2) — "계란 2개"를 "계란" 그룹에 넣어, 냉장고에 계란이
     * 있으면 있는 것으로 치게 한다. 같은 엔드포인트로 해제도 한다(matchKey == name).
     *
     * <p>묶기는 오너 확정만 — AI 는 제안까지만이고 자동 병합 경로는 없다(안전 비대칭 규칙).
     * 없는 이름·사전에 없는 대표 = 404.
     */
    @PostMapping("/dictionary/merge")
    public void mergeIngredient(@GikkaUserId long userId, @RequestBody MergeRequest request) {
        requireOwner(userId);
        if (isBlankMerge(request)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이름·대표 누락");
        }
        if (!dictionary.merge(request.name().trim(), request.matchKey().trim())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "사전에 없는 이름 또는 대표");
        }
    }

    /** 오너 일괄 그룹 확정 — [AI 점검] 병합 제안 전체 적용용. classify-batch 와 같은 이유로
        없는 이름은 조용히 건너뛴다(한 건 때문에 전체를 실패시키지 않는다).
        @return 실제로 바뀐 건수 (프론트는 재조회로 화면을 맞춘다 — 확인·로그용) */
    @PostMapping("/dictionary/merge-batch")
    public int mergeIngredients(@GikkaUserId long userId, @RequestBody List<MergeRequest> requests) {
        requireOwner(userId);
        if (requests == null || requests.stream().anyMatch(RegistrationController::isBlankMerge)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이름·대표 누락");
        }
        return dictionary.mergeAll(requests.stream()
                .collect(Collectors.toMap(r -> r.name().trim(), r -> r.matchKey().trim(), (a, b) -> b)));
    }

    // AI 일괄 점검(/dictionary/audit)은 IngredientAuditController 로 옮겼다 (2026-07-17) —
    // 동기 LLM 호출이라 응답이 10~25초라서 nginx `location /api` 의 15초에 끊겼다.
    // 전용 경로 /api/recipe/llm/** 로 분리. 근거는 그 클래스 주석 참고.

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
                // 검색 보완(others)은 내 등록이 아니라 registeredAt 이 null — 이 경우 null 로 내려간다
                // (프론트 GikkaVideo 는 registeredAt 을 안 쓴다). 내 등록 항목은 항상 값이 있다.
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
