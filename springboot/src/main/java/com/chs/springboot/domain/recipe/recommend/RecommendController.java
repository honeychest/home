// [AGENT] 추천 API — 경로 /api/recipe/recommend (분리 규율 3), 응답은 프론트 recommendTypes.ts 계약
// 인증: @GikkaUserId. 계산은 LLM 없이 재료 태그 집합 비교(CONTEXT.md 확정) — 순수 판정은
// RecommendRules 가 담당, 여기는 냉장고·완료된 레시피를 모아 넘기는 조합만 한다.
package com.chs.springboot.domain.recipe.recommend;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.chs.springboot.domain.recipe.auth.GikkaUserId;
import com.chs.springboot.domain.recipe.fridge.FridgeItemResponse;
import com.chs.springboot.domain.recipe.fridge.FridgeRepository;
import com.chs.springboot.domain.recipe.registration.RegistrationRepository;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/recipe/recommend")
public class RecommendController {

    private final RegistrationRepository registrations;
    private final FridgeRepository fridge;
    private final RecommendCandidateCache cache;

    public RecommendController(RegistrationRepository registrations, FridgeRepository fridge,
                              RecommendCandidateCache cache) {
        this.registrations = registrations;
        this.fridge = fridge;
        this.cache = cache;
    }

    /** missing: 완전 가능=빈 목록, 양념만 부족=부족한 양념 이름, 재료 부족=부족한 재료 이름
        (개수 상한 없음 — 2026-07-14 확정, 부족 적은 순으로 항상 표시).
        ingredients·cookMinutes·steps: 카드 탭 → 상세 팝업용(2026-07-14 확정).
        inLibrary: 이 레시피가 내 보관함에 있는지 (2026-07-16 5차 — 추천 풀이 gikka 전체로 넓어져
        남의 레시피도 뜨므로, 카드 배지·"담기" 버튼 노출을 프론트가 판단하게 표시) */
    public record RecommendItem(String videoId, String url, String title, String thumbnailUrl,
                                List<String> missing, List<RecommendRules.IngredientStatus> ingredients,
                                Integer cookMinutes, List<String> steps, boolean inLibrary) {
    }

    public record RecommendSnapshot(List<RecommendItem> complete, List<RecommendItem> seasoningOnly,
                                    List<RecommendItem> needsIngredients) {
    }

    @GetMapping
    public RecommendSnapshot recommend(@GikkaUserId long userId) {
        var fridgeItems = fridge.list(userId);
        var fridgeNames = fridgeItems.stream()
                .map(FridgeItemResponse::name).collect(Collectors.toSet());
        // 임박 재료 — 이걸 쓰는 레시피를 정렬에서 우대한다 (2026-07-18)
        var expiringNames = fridgeItems.stream().filter(FridgeItemResponse::expiring)
                .map(FridgeItemResponse::name).collect(Collectors.toSet());
        // 후보는 gikka 전체 완료 요리(콜드스타트 대응) — 냉장고 매칭은 그대로 내 것 기준.
        // 내 등록 videoId 집합은 "내 보관함" 표시 + 섹션 상한(내 것 10/남의 것 10)에 쓴다.
        Set<String> myVideoIds = new HashSet<>(registrations.videoIdsForUser(userId));
        // 전 사용자 공통 입력(후보 목록·사전)은 캐시에서 — 요청당 DB 는 위 개인 쿼리 2개뿐 (2026-07-18)
        RecommendCandidateCache.Snapshot snapshot = cache.get();
        var dict = snapshot.dictionary();
        Set<String> fridgeKeys = RecommendRules.keysOf(fridgeNames, dict); // 후보마다 다시 계산하지 않게 한 번만
        Set<String> expiringKeys = RecommendRules.keysOf(expiringNames, dict);

        List<RecommendRules.Match> matches = snapshot.candidates().stream()
                .map(candidate -> RecommendRules.match(candidate, fridgeKeys, expiringKeys, dict))
                .toList();

        // 일일 셔플 시드 — 사용자+오늘 날짜로 하루 동안 고정, 날이 바뀌면 동점 순서가 회전한다.
        // 서버 LocalDate.now() 기준(TZ=Asia/Seoul 전제 — CONTEXT.md "시간 기준" 절과 동일한 한계).
        long shuffleSeed = userId * 31L + LocalDate.now().hashCode();
        RecommendRules.Sections sections = RecommendRules.bucket(matches, myVideoIds, shuffleSeed);
        return new RecommendSnapshot(
                toItems(sections.complete(), myVideoIds),
                toItems(sections.seasoningOnly(), myVideoIds),
                toItems(sections.needsIngredients(), myVideoIds));
    }

    /** 구매 추천 한 줄 — 냉장고 재료 추가 시트 하단용 (2026-07-19 확정) */
    public record ShoppingItem(String name, List<String> recipes) {
    }

    /** "이거 하나만 사면 만들 수 있어요" — 내 보관함 레시피 중 주재료 1개 부족인 것의 집계.
        임박 가중치는 정렬에 안 쓰므로 expiring 은 빈 집합으로 매칭한다. */
    @GetMapping("/shopping")
    public List<ShoppingItem> shopping(@GikkaUserId long userId) {
        var fridgeNames = fridge.list(userId).stream()
                .map(FridgeItemResponse::name).collect(Collectors.toSet());
        Set<String> myVideoIds = new HashSet<>(registrations.videoIdsForUser(userId));
        RecommendCandidateCache.Snapshot snapshot = cache.get();
        var dict = snapshot.dictionary();
        Set<String> fridgeKeys = RecommendRules.keysOf(fridgeNames, dict);

        List<RecommendRules.Match> matches = snapshot.candidates().stream()
                .map(candidate -> RecommendRules.match(candidate, fridgeKeys, Set.of(), dict))
                .toList();
        return RecommendRules.shoppingSuggestions(matches, myVideoIds, dict).stream()
                .map(s -> new ShoppingItem(s.name(), s.recipeTitles()))
                .toList();
    }

    // recipe_json 파싱(toCandidate)은 RecommendCandidateCache 로 이관 (2026-07-18 — 캐시가
    // 파싱 결과를 들고 있어야 요청마다 재파싱이 없어진다)

    private List<RecommendItem> toItems(List<RecommendRules.Match> matches, Set<String> myVideoIds) {
        return matches.stream().map(match -> toItem(match, myVideoIds)).toList();
    }

    private RecommendItem toItem(RecommendRules.Match match, Set<String> myVideoIds) {
        RecommendRules.Candidate c = match.candidate();
        List<String> missing = !match.missingMain().isEmpty() ? match.missingMain() : match.missingSeasoning();
        return new RecommendItem(c.videoId(), c.url(), c.title(), c.thumbnailUrl(), missing,
                match.ingredientStatuses(), c.cookMinutes(), c.steps(), myVideoIds.contains(c.videoId()));
    }
}
