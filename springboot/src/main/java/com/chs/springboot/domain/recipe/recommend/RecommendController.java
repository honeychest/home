// [AGENT] 추천 API — 경로 /api/recipe/recommend (분리 규율 3), 응답은 프론트 recommendTypes.ts 계약
// 인증: @GikkaUserId. 계산은 LLM 없이 재료 태그 집합 비교(CONTEXT.md 확정) — 순수 판정은
// RecommendRules 가 담당, 여기는 냉장고·완료된 레시피를 모아 넘기는 조합만 한다.
package com.chs.springboot.domain.recipe.recommend;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.chs.springboot.domain.recipe.auth.GikkaUserId;
import com.chs.springboot.domain.recipe.fridge.FridgeItemResponse;
import com.chs.springboot.domain.recipe.fridge.FridgeRepository;
import com.chs.springboot.domain.recipe.registration.IngredientDictionaryRepository;
import com.chs.springboot.domain.recipe.registration.RegistrationRepository;
import com.chs.springboot.domain.recipe.registration.VideoRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/recipe/recommend")
public class RecommendController {

    private final RegistrationRepository registrations;
    private final VideoRepository videos;
    private final FridgeRepository fridge;
    private final IngredientDictionaryRepository dictionary;
    private final ObjectMapper mapper = new ObjectMapper();

    public RecommendController(RegistrationRepository registrations, VideoRepository videos,
                              FridgeRepository fridge, IngredientDictionaryRepository dictionary) {
        this.registrations = registrations;
        this.videos = videos;
        this.fridge = fridge;
        this.dictionary = dictionary;
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
        // 사전 3종은 늘 같이 다닌다 — 저장소가 멤버까지 대표 기준으로 펼쳐서 주므로 여기선 조립만.
        // basicNames = 상비 양념(부족분에서 아예 뺀다), matchKeys = 그룹 매칭 키(2026-07-17 슬라이스2)
        var dict = new RecommendRules.Dictionary(
                dictionary.matchKeys(), dictionary.seasoningNames(), dictionary.basicNames());
        Set<String> fridgeKeys = RecommendRules.keysOf(fridgeNames, dict); // 후보마다 다시 계산하지 않게 한 번만
        Set<String> expiringKeys = RecommendRules.keysOf(expiringNames, dict);

        List<RecommendRules.Match> matches = videos.allDoneRecipes().stream()
                .map(this::toCandidate)
                .flatMap(Optional::stream)
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

    /** recipe_json 이 없으면(이론상 RECIPE+DONE 인데 비어있는 이상 상태) 추천 대상에서 조용히 제외 */
    private Optional<RecommendRules.Candidate> toCandidate(VideoRepository.Row row) {
        if (row.recipeJson() == null) {
            return Optional.empty();
        }
        JsonNode node;
        try {
            node = mapper.readTree(row.recipeJson());
        } catch (Exception e) {
            throw new IllegalStateException("recipe_json 파싱 실패: video=" + row.videoId(), e);
        }
        String name = node.hasNonNull("name") && !node.get("name").asText().isBlank()
                ? node.get("name").asText() : row.title();
        List<String> ingredients = new ArrayList<>();
        node.path("ingredients").forEach(n -> ingredients.add(n.asText()));
        Integer cookMinutes = node.hasNonNull("cookMinutes") ? node.get("cookMinutes").asInt() : null;
        List<String> steps = new ArrayList<>();
        node.path("steps").forEach(n -> steps.add(n.asText()));
        return Optional.of(new RecommendRules.Candidate(
                row.videoId(), row.url(), name, row.thumbnailUrl(), ingredients, cookMinutes, steps));
    }

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
