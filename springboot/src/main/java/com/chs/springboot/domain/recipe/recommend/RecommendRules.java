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
import java.util.Set;

final class RecommendRules {

    static final int MAX_PER_SECTION = 5;

    private RecommendRules() {
    }

    /* ── 1. 재료/양념 분류 (2026-07-17 5차-4 슬라이스1 — DB 사전으로 이관) ──
       분류 목록은 이제 코드 상수가 아니라 재료 사전(ingredient_dictionary)이 소유한다.
       이 순수 모듈은 DB 를 모른다 — 호출부(RecommendController)가 사전에서 양념 이름 집합을
       읽어 인자로 넘긴다(pattern-pure-rules 유지, 테스트는 집합만 주입하면 됨).
       seasoningNames 에 든 이름만 SEASONING, 나머지는 전부 MAIN(판정 전 이름을 양념으로 잘못
       빼면 추천에서 사라지므로 MAIN 이 안전 기본값 — 사전의 PENDING/SKIPPED 도 tier=MAIN 으로
       내려온다). 구 ALIASES("다진 마늘"="다진마늘" 등)는 사전에 양쪽 표기를 모두 양념으로
       시드해 흡수됨(슬라이스1은 이름 정확 매칭 — 그룹 병합은 슬라이스2에서 match_key 로). */

    /** BASIC = 집에 늘 있는 상비 양념(물·소금·설탕…) — 부족분에서 아예 뺀다. SEASONING(고추장·
        굴소스…)과 달리 "양념만 부족" 섹션에도 안 남는다. 이게 없으면 완전 가능 섹션이 구조적으로
        항상 0이다(레시피 115개 중 48개가 "물"을 재료로 적는데 냉장고엔 물이 없음 — 2026-07-17 실측). */
    enum Tier { MAIN, SEASONING, BASIC }

    static Tier classify(String ingredientName, Set<String> seasoningNames, Set<String> basicNames) {
        if (basicNames.contains(ingredientName)) {
            return Tier.BASIC;
        }
        return seasoningNames.contains(ingredientName) ? Tier.SEASONING : Tier.MAIN;
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

    /** 재료 원문 그대로 정확 매칭(fridgeNames 는 사용자 냉장고 재료 이름 집합, seasoningNames·
        basicNames 는 사전에서 온 이름 집합 — 부족분을 주재료/양념으로 가르고 기본양념은 뺀다) */
    static Match match(Candidate candidate, Set<String> fridgeNames, Set<String> seasoningNames,
                       Set<String> basicNames) {
        List<String> missingMain = new ArrayList<>();
        List<String> missingSeasoning = new ArrayList<>();
        for (String raw : candidate.ingredients()) {
            String name = raw == null ? "" : raw.trim();
            if (name.isEmpty() || fridgeNames.contains(name)) {
                continue;
            }
            Tier tier = classify(name, seasoningNames, basicNames);
            if (tier == Tier.BASIC) {
                continue; // 늘 있다고 간주 — 부족분에 안 셈
            }
            if (tier == Tier.SEASONING) {
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
