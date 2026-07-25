// [AGENT] 운영자 모드 대기열 API — 오너 전용 (/api/recipe/registrations/monitor/**).
// 2026-07-25 RegistrationController 에서 분리: 그 클래스가 보관함·모니터·사전 세 청중을 한
// 파일(449줄·협력자 9개)에 담고 있어 테스트가 mock 9개를 세워야 했다. 경로는 그대로다.
//
// "오너 전용 강제 동작(강제 재분석·영상 삭제)은 전부 여기 한 곳에 모은다" 정책(2026-07-13,
// CONTEXT.md §14)의 백엔드 짝 — 일반 화면에 조건부 버튼으로 흩뿌리지 않는다.
// 실제 경계는 GikkaOwnerGuard 의 403 이다(탭 바에 없다는 건 경계가 아니다).
package com.chs.springboot.domain.recipe.registration;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.chs.springboot.domain.recipe.auth.GikkaOwnerGuard;
import com.chs.springboot.domain.recipe.auth.GikkaUserId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/recipe/registrations/monitor")
public class MonitorController {

    private final RegistrationRepository repository;
    private final VideoRepository videos;
    private final GeminiRateLimiter rateLimiter;
    private final VideoMetadataClient metadata;
    private final GikkaOwnerGuard owner;
    /** 로컬 추출기 상태 표시용 — Hybrid(@Primary)가 아니라 로컬 구현체를 직접 받는다.
        "로컬이 지금 쓸 수 있는 상태인가"는 라우팅과 무관한 로컬 자신의 사실이므로 (2026-07-16) */
    private final LocalRecipeExtractor localExtractor;
    private final ObjectMapper mapper = new ObjectMapper();

    public MonitorController(RegistrationRepository repository, VideoRepository videos,
                             GeminiRateLimiter rateLimiter, VideoMetadataClient metadata,
                             GikkaOwnerGuard owner, LocalRecipeExtractor localExtractor) {
        this.repository = repository;
        this.videos = videos;
        this.rateLimiter = rateLimiter;
        this.metadata = metadata;
        this.owner = owner;
        this.localExtractor = localExtractor;
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
        모니터링 화면의 카운트다운 표시용, gemini_rate.last_call_at 재사용).
        localExtractor: 호스트 서비스가 스스로 보고한 사실 그대로(JSON 통과). 서비스가 죽었거나
        /health 가 없는 옛 버전이면 null — 그 null 자체가 "지금 로컬을 못 쓴다"는 사실이다.
        판정·문구는 프론트가 한다 (pattern-raw-signal) */
    public record MonitorSnapshot(int waitingCount, int analyzingCount, String workerHeartbeatAt,
                                  int rateLimitCount, String lastRateLimitedAt, String nextRetryAt,
                                  JsonNode localExtractor,
                                  Map<String, Integer> statusCounts,
                                  List<MonitorResponse> items) {
    }

    /** 전 사용자 대기열 실시간 조회 — 오너 전용 (허용 목록 재사용, 목록 비면 아무도 접근 불가).
        q(제목·요리명·태그 검색)·status 는 선택 필터 (2026-07-18 — 최신 limit 개 밖 영상 탐색용) */
    @GetMapping
    public MonitorSnapshot monitor(@GikkaUserId long userId, @RequestParam int limit,
                                   @RequestParam(required = false) String q,
                                   @RequestParam(required = false) String status) {
        owner.require(userId);
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

    @GetMapping("/{videoId}/analysis")
    public MonitorAnalysisResponse monitorAnalysis(@GikkaUserId long userId, @PathVariable String videoId) {
        owner.require(userId);
        VideoRepository.Row row = videos.findById(videoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "없는 영상"));
        return new MonitorAnalysisResponse(
                row.category(),
                readJson(row.recipeJson(), "recipe_json", row.videoId()),
                row.summary(),
                readJson(row.tagsJson(), "tags", row.videoId()),
                readJson(row.analysisSignalsJson(), "analysis_signals", row.videoId()));
    }

    /** 강제 재분석 — 오너 전용, 요청자 본인이 등록했는지 여부와 무관 (2026-07-13 확정).
        메타도 함께 재조회 (일반 재분석과 동일 이유) */
    @PostMapping("/{videoId}/reanalyze")
    public void monitorReanalyze(@GikkaUserId long userId, @PathVariable String videoId) {
        owner.require(userId);
        if (!videos.reanalyze(videoId, metadata.fetchOne(videoId))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "없는 영상");
        }
    }

    /**
     * 영상 삭제 — 오너 전용 (2026-07-13 확정, 원본이 유튜브에서 삭제·비공개된 경우 등).
     * 영상정보는 남기고 분석정보만 지워 REMOVED 로 표시 — 등록했던 다른 사용자 목록에도
     * "삭제됨"으로 보임(연결은 유지). 재분석하면 평소 파이프라인을 다시 타며 자동 복구됨.
     */
    @PostMapping("/{videoId}/remove")
    public void monitorRemove(@GikkaUserId long userId, @PathVariable String videoId) {
        owner.require(userId);
        if (!videos.remove(videoId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "없는 영상");
        }
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
