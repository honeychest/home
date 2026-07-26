// [AGENT] 재료 사전 관리 API — 오너 전용(자동완성 1개만 예외). 2026-07-25 RegistrationController
// 에서 분리: 그 클래스가 보관함·모니터·사전 세 청중을 한 파일(449줄·협력자 9개)에 담고 있어,
// 사전 엔드포인트 하나를 검증하려 해도 mock 9개를 세워야 했다.
//
// **경로가 `/registrations/dictionary` 인 것은 유산이다** (사전은 등록과 무관하다). 지금 안 바꾸는
// 이유: 경로를 바꾸면 프론트(monitorRepository·fridgeRepository)와 동시 배포가 필요해, "구조 정리"
// 라는 이번 작업에 다른 축의 위험이 섞인다. `gikka/` 백엔드 분리 때 라우팅을 어차피 다시 보므로
// 그때 `/api/recipe/dictionary/**` 로 한 번에 옮긴다 (2026-07-25 사용자 확정).
//
// 오너 전용 강제 동작은 운영자 모드 한 곳에 모은다는 정책(CONTEXT.md §14)의 백엔드 짝이다 —
// 화면은 /recipe/monitor/dictionary 하나뿐이고, 실제 경계는 GikkaOwnerGuard 의 403 이다.
package com.chs.gikka.dictionary;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.chs.gikka.auth.GikkaOwnerGuard;
import com.chs.gikka.auth.GikkaUserId;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/recipe/registrations/dictionary")
public class DictionaryController {

    private final IngredientDictionaryRepository dictionary;
    private final IngredientChangeLogRepository changeLog;
    private final GikkaOwnerGuard owner;

    public DictionaryController(IngredientDictionaryRepository dictionary,
                                IngredientChangeLogRepository changeLog, GikkaOwnerGuard owner) {
        this.dictionary = dictionary;
        this.changeLog = changeLog;
        this.owner = owner;
    }

    /** 오너 사전 관리 화면용 전체 목록 */
    @GetMapping
    public List<IngredientDictionaryRepository.Entry> dictionary(@GikkaUserId long userId) {
        owner.require(userId);
        return dictionary.all();
    }

    /** 냉장고 재료 추가 자동완성용 대표 이름 목록 (2026-07-19 확정) — 오너 아님, 로그인 사용자
        공용(사용자 오탈자 예방이 목적이라 모두에게 열려야 의미가 있음). 이름 목록만 노출 —
        status·그룹 등 관리 정보는 위 오너 전용 계약에만 있다. */
    @GetMapping("/names")
    public List<String> dictionaryNames() {
        return dictionary.representativeNames();
    }

    /** 자동 반영 사후 감사용 (2026-07-18) — 파이프라인이 사전을 스스로 바꾼 최근 내역.
        개수 상한은 이 상수 하나가 원본 (사후 감사는 "최근 것 훑기"라 페이징 불필요). */
    private static final int CHANGE_LOG_LIMIT = 50;

    @GetMapping("/changes")
    public List<IngredientChangeLogRepository.Entry> dictionaryChanges(@GikkaUserId long userId) {
        owner.require(userId);
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
    @PostMapping("/classify")
    public void classifyIngredient(@GikkaUserId long userId, @RequestBody ClassifyRequest request) {
        owner.require(userId);
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
    @PostMapping("/classify-batch")
    public int classifyIngredients(@GikkaUserId long userId, @RequestBody List<ClassifyRequest> requests) {
        owner.require(userId);
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
    @PostMapping("/merge")
    public void mergeIngredient(@GikkaUserId long userId, @RequestBody MergeRequest request) {
        owner.require(userId);
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
    @PostMapping("/merge-batch")
    public int mergeIngredients(@GikkaUserId long userId, @RequestBody List<MergeRequest> requests) {
        owner.require(userId);
        if (requests == null || requests.stream().anyMatch(DictionaryController::isBlankMerge)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이름·대표 누락");
        }
        return dictionary.mergeAll(requests.stream()
                .collect(Collectors.toMap(r -> r.name().trim(), r -> r.matchKey().trim(), (a, b) -> b)));
    }
}
