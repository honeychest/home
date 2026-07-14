// [AGENT] 추천 알고리즘 — 이 파일 하나가 단일 원본이다 (2026-07-14 확정, 사용자 요청 —
// "알고리즘 관련 로직은 파일 하나에 정리돼 있어야 나중에 수정할 곳을 파악하기 쉽다").
// 재료/양념 분류, 매칭, 3단계 판정·정렬까지 전부 여기에 둔다. 새 정렬 기준을 추가할 때
// (예: 임박 재료가 많이 들어간 순서를 우선하는 가중치 — 2026-07-14 시점엔 미적용, 아이디어만
// 남김)도 이 파일 안에서만 고치면 된다. DB·HTTP 없이 검증하는 순수 모듈(RegistrationRules 패턴).
package com.chs.springboot.domain.recipe.recommend;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RecommendRules {

    static final int MAX_PER_SECTION = 5;

    private RecommendRules() {
    }

    /* ── 1. 재료/양념 분류 (CONTEXT.md 4차 확정) ──
       정규화(대파=파 통합 등)는 원칙적으로 하지 않는다 — 원문 그대로 정확 매칭.
       단, 실사용 데이터(2026-07-14, "캔참치" 레시피 채점)로 발견된 명백한 표기 차이만
       예외로 별칭(ALIASES) 처리한다 — "다진 마늘"(띄어쓰기)="다진마늘", "백식초"="식초"
       (사용자 확인: 둘 다 "같은 걸 가리키는 표기 차이"). 반대로 "맛소금"≠"소금",
       "진간장"·"양조간장"≠"간장"은 실제로 다른 재료라는 사용자 판단으로 합치지 않는다
       (여기가 바로 "정규화가 필요한지 애매하다"던 지점 — 표기 차이만 좁게 흡수하고
       종류가 다른 건 그대로 둔다는 원칙으로 정리됨).
       목록은 오너가 정한 코드 상수(관리 화면 없음 — 이 규모에서는 화면보다 코드 배포가
       더 가볍다는 판단). 확장 여지: 나중에 SEASONING 을 BASIC/SPECIAL 로 세분화하거나
       사용자별 목록으로 바꿀 때 이 안(Tier·SEASONINGS·ALIASES·classify)만 고치면 된다 —
       호출부(match)는 Tier 값만 본다. */

    enum Tier { MAIN, SEASONING }

    private static final Set<String> SEASONINGS = Set.of(
            "물", "소금", "설탕", "간장", "고추장", "고춧가루", "된장", "후추", "식용유",
            "참기름", "들기름", "다진마늘", "다진생강", "맛술", "식초", "물엿", "올리고당",
            "전분가루", "통깨"
    );

    /** 표기 차이만 좁게 흡수(2026-07-14 확정) — 종류가 다른 재료는 여기 넣지 않는다 */
    private static final Map<String, String> ALIASES = Map.of(
            "다진 마늘", "다진마늘",
            "백식초", "식초"
    );

    static Tier classify(String ingredientName) {
        String canonical = ALIASES.getOrDefault(ingredientName, ingredientName);
        return SEASONINGS.contains(canonical) ? Tier.SEASONING : Tier.MAIN;
    }

    /* ── 2. 매칭 ── */

    /** 추천 대상 하나 (video+registration 조인 결과에서 뽑아낸 것). cookMinutes·steps 는
        클릭 시 상세 팝업용(2026-07-14 확정) — 카드 자체엔 안 씀 */
    record Candidate(String videoId, String url, String title, String thumbnailUrl, List<String> ingredients,
                     Integer cookMinutes, List<String> steps) {
    }

    /** 상세 팝업용 재료 한 줄 — 원문 순서 그대로, 있음/없음만 표시(2026-07-14 확정) */
    record IngredientStatus(String name, boolean have) {
    }

    record Match(Candidate candidate, List<String> missingMain, List<String> missingSeasoning) {

        boolean isComplete() {
            return missingMain.isEmpty() && missingSeasoning.isEmpty();
        }

        boolean isSeasoningOnly() {
            return missingMain.isEmpty() && !missingSeasoning.isEmpty();
        }

        /** 완전 가능도 양념만 부족도 아니면(=재료가 하나라도 없으면) 전부 "재료 부족" 대상
            (2026-07-14 확정 — 부족 개수 상한으로 제외하지 않는다. 실사용 발견: 몇 개가
            부족하든 항상 부족한 순서대로 보여줘야 추천이 의미가 있음, 아무것도 안 보이는
            게 오히려 나쁨) */
        boolean isMissingMain() {
            return !missingMain.isEmpty();
        }

        /** 상세 팝업의 재료 목록(원문 순서 유지) — 이미 계산해둔 missingMain/missingSeasoning 을
            재사용해 매칭을 다시 하지 않는다 */
        List<IngredientStatus> ingredientStatuses() {
            Set<String> missing = new HashSet<>(missingMain);
            missing.addAll(missingSeasoning);
            List<IngredientStatus> result = new ArrayList<>();
            for (String raw : candidate.ingredients()) {
                String name = raw == null ? "" : raw.trim();
                if (name.isEmpty()) {
                    continue;
                }
                result.add(new IngredientStatus(name, !missing.contains(name)));
            }
            return result;
        }
    }

    /** 재료 원문 그대로 정확 매칭(fridgeNames 는 사용자 냉장고 재료 이름 집합) */
    static Match match(Candidate candidate, Set<String> fridgeNames) {
        List<String> missingMain = new ArrayList<>();
        List<String> missingSeasoning = new ArrayList<>();
        for (String raw : candidate.ingredients()) {
            String name = raw == null ? "" : raw.trim();
            if (name.isEmpty() || fridgeNames.contains(name)) {
                continue;
            }
            if (classify(name) == Tier.SEASONING) {
                missingSeasoning.add(name);
            } else {
                missingMain.add(name);
            }
        }
        return new Match(candidate, missingMain, missingSeasoning);
    }

    /* ── 3. 3단계 판정·정렬 (2026-07-14 4차 확정) ──
       정렬 기준은 지금은 "재료 부족 섹션만 부족 개수 오름차순" 하나뿐이다. 향후 후보로
       논의된 기준(아직 미적용, 여기에 추가할 것): 임박 재료(냉장고 expiring=true)를
       많이 쓰는 레시피를 우선순위로 올리는 가중치 — 도입 시 Candidate 에 재료별 임박
       여부를 실어 보내고 이 절의 정렬 Comparator 만 바꾸면 된다. */

    record Sections(List<Match> complete, List<Match> seasoningOnly, List<Match> needsIngredients) {
    }

    /** 완전 가능 / 양념만 부족 / 재료 부족(부족 적은 순, 상한 없음) — 각 섹션 최대 5개 */
    static Sections bucket(List<Match> matches) {
        List<Match> complete = matches.stream().filter(Match::isComplete).limit(MAX_PER_SECTION).toList();
        List<Match> seasoningOnly = matches.stream().filter(Match::isSeasoningOnly).limit(MAX_PER_SECTION).toList();
        List<Match> needsIngredients = matches.stream().filter(Match::isMissingMain)
                .sorted(Comparator.comparingInt(m -> m.missingMain().size()))
                .limit(MAX_PER_SECTION).toList();
        return new Sections(complete, seasoningOnly, needsIngredients);
    }
}
