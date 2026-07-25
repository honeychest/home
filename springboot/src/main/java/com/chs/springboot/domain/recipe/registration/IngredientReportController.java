// [AGENT] 재료 신고 API (2026-07-18 — CONTEXT.md "재료 신고(전력 재분석)" 절).
// 이건 **일반 사용자 기능**이다 — 지금은 공개 전이라 허용 목록(오너)으로 게이트만 걸어 둔 것
// (2026-07-18 사용자 확정: "사용자가 쓸 기능을 우선 나한테만 보이게"). 공개 시점에 아래
// requireOwner 호출만 제거하면 된다. "오너 전용 강제 동작은 monitor 한 곳"(2026-07-13) 정책과
// 상충하지 않는다 — 그 정책은 영구 오너 기능용이고 이건 일반 기능의 임시 게이트다.
// 접수는 즉시 응답(기록만)하고 처리(전력 재분석)는 워커가 백그라운드로 — 사용자를 LLM 지연에
// 붙잡지 않는다. 임계값·1인 1신고·실행 상한 판정은 전부 IngredientReportRepository 소관.
package com.chs.springboot.domain.recipe.registration;

import java.util.List;
import java.util.Map;

import com.chs.springboot.domain.recipe.auth.GikkaOwnerGuard;
import com.chs.springboot.domain.recipe.auth.GikkaUserId;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/recipe/ingredient-reports")
public class IngredientReportController {

    private final IngredientReportRepository reports;
    private final VideoRepository videos;
    private final GikkaOwnerGuard owner;

    public IngredientReportController(IngredientReportRepository reports, VideoRepository videos,
                                      GikkaOwnerGuard owner) {
        this.reports = reports;
        this.videos = videos;
        this.owner = owner;
    }

    public record ReportRequest(String videoId, String name) {
    }

    /** 접수 응답 — reported(이번에 접수) / already(이미 접수돼 처리 대기 중). 문구는 프론트 소유 */
    @PostMapping
    public Map<String, String> report(@GikkaUserId long userId, @RequestBody ReportRequest request) {
        requireOwnerWhileGated(userId);
        if (request == null || request.videoId() == null || request.videoId().isBlank()
                || request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "영상·재료 누락");
        }
        if (!videos.existsActive(request.videoId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "없는 영상");
        }
        boolean accepted = reports.report(request.videoId(), request.name().trim(), userId);
        return Map.of("outcome", accepted ? "reported" : "already");
    }

    /** 내가 이 영상에서 접수해 둔(처리 안 끝난) 재료 이름들 — 결과 시트의 신고 버튼 상태용 */
    @GetMapping
    public List<String> myActiveReports(@GikkaUserId long userId, @RequestParam String videoId) {
        requireOwnerWhileGated(userId);
        return reports.activeNames(videoId, userId);
    }

    /**
     * 공개 전 임시 게이트 — 공개 시 이 호출만 제거 (허용 목록이 비면[공개 상태] 아무도 못 씀).
     *
     * <p>판정은 GikkaOwnerGuard 와 같은 것을 쓰지만 <b>이름은 일부러 따로 둔다</b> (2026-07-25).
     * 이건 "오너 전용 기능"이 아니라 <b>일반 기능의 공개 전 게이트</b>라서다(CONTEXT.md §10).
     * 이름까지 owner.require 로 합치면, 공개할 때 지워야 할 게 어느 호출인지 구분이 사라진다.
     */
    private void requireOwnerWhileGated(long userId) {
        if (!owner.isOwner(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "공개 전 기능");
        }
    }
}
