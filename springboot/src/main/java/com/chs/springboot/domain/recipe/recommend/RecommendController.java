// [AGENT] 추천 API — 경로 /api/recipe/recommend (분리 규율 3), 응답은 프론트 recommendTypes.ts 계약
// 인증: @GikkaUserId. 계산은 LLM 없이 재료 태그 집합 비교(CONTEXT.md 확정) — 순수 판정은
// RecommendRules 가 담당, 여기는 냉장고·완료된 레시피를 모아 넘기는 조합만 한다.
package com.chs.springboot.domain.recipe.recommend;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import com.chs.springboot.domain.recipe.auth.GikkaUserId;
import com.chs.springboot.domain.recipe.fridge.FridgeItemResponse;
import com.chs.springboot.domain.recipe.fridge.FridgeRepository;
import com.chs.springboot.domain.recipe.registration.RegistrationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/recipe/recommend")
public class RecommendController {

    private final RegistrationRepository registrations;
    private final FridgeRepository fridge;
    private final ObjectMapper mapper = new ObjectMapper();

    public RecommendController(RegistrationRepository registrations, FridgeRepository fridge) {
        this.registrations = registrations;
        this.fridge = fridge;
    }

    /** missing: 완전 가능=빈 목록, 양념만 부족=부족한 양념 이름, 재료 부족=부족한 재료 이름
        (개수 상한 없음 — 2026-07-14 확정, 부족 적은 순으로 항상 표시).
        ingredients·cookMinutes·steps: 카드 탭 → 상세 팝업용(2026-07-14 확정) */
    public record RecommendItem(String videoId, String url, String title, String thumbnailUrl,
                                List<String> missing, List<RecommendRules.IngredientStatus> ingredients,
                                Integer cookMinutes, List<String> steps) {
    }

    public record RecommendSnapshot(List<RecommendItem> complete, List<RecommendItem> seasoningOnly,
                                    List<RecommendItem> needsIngredients) {
    }

    @GetMapping
    public RecommendSnapshot recommend(@GikkaUserId long userId) {
        var fridgeNames = fridge.list(userId).stream()
                .map(FridgeItemResponse::name).collect(Collectors.toSet());

        List<RecommendRules.Match> matches = registrations.recipesForUser(userId).stream()
                .map(this::toCandidate)
                .flatMap(Optional::stream)
                .map(candidate -> RecommendRules.match(candidate, fridgeNames))
                .toList();

        RecommendRules.Sections sections = RecommendRules.bucket(matches);
        return new RecommendSnapshot(
                sections.complete().stream().map(this::toItem).toList(),
                sections.seasoningOnly().stream().map(this::toItem).toList(),
                sections.needsIngredients().stream().map(this::toItem).toList());
    }

    /** recipe_json 이 없으면(이론상 RECIPE+DONE 인데 비어있는 이상 상태) 추천 대상에서 조용히 제외 */
    private Optional<RecommendRules.Candidate> toCandidate(RegistrationRepository.Row row) {
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

    private RecommendItem toItem(RecommendRules.Match match) {
        RecommendRules.Candidate c = match.candidate();
        List<String> missing = !match.missingMain().isEmpty() ? match.missingMain() : match.missingSeasoning();
        return new RecommendItem(c.videoId(), c.url(), c.title(), c.thumbnailUrl(), missing,
                match.ingredientStatuses(), c.cookMinutes(), c.steps());
    }
}
