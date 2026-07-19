// [AGENT] 추천 알고리즘 — 이 파일 하나가 단일 원본이다 (2026-07-14 확정, 사용자 요청 —
// "알고리즘 관련 로직은 파일 하나에 정리돼 있어야 나중에 수정할 곳을 파악하기 쉽다").
// 재료/양념 분류, 매칭, 3단계 판정·정렬까지 전부 여기에 둔다. 새 정렬 기준을 추가할 때
// (예: 임박 재료가 많이 들어간 순서를 우선하는 가중치 — 2026-07-14 시점엔 미적용, 아이디어만
// 남김)도 이 파일 안에서만 고치면 된다. DB·HTTP 없이 검증하는 순수 모듈(RegistrationRules 패턴).
package com.chs.springboot.domain.recipe.recommend;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class RecommendRules {

    /** 섹션당 상한 — 내 보관함 10 + 남의 것 10 = 20 (2026-07-18 사용자 확정, 구 "5+5" 계획을 대체) */
    static final int MAX_MINE_PER_SECTION = 10;
    static final int MAX_OTHERS_PER_SECTION = 10;

    private RecommendRules() {
    }

    /* ── 1. 재료/양념 분류 (2026-07-17 5차-4 슬라이스1 — DB 사전으로 이관) ──
       분류 목록은 이제 코드 상수가 아니라 재료 사전(ingredient_dictionary)이 소유한다.
       이 순수 모듈은 DB 를 모른다 — 호출부(RecommendController)가 사전을 읽어 Dictionary 로
       넘긴다(pattern-pure-rules 유지, 테스트는 값만 주입하면 됨).
       seasoningNames 에 든 이름만 SEASONING, 나머지는 전부 MAIN(판정 전 이름을 양념으로 잘못
       빼면 추천에서 사라지므로 MAIN 이 안전 기본값 — 사전의 PENDING/SKIPPED 도 tier=MAIN 으로
       내려온다). 구 ALIASES("다진 마늘"="다진마늘" 등)는 사전에 양쪽 표기를 모두 양념으로 시드해 흡수됨. */

    /**
     * 매칭이 필요로 하는 사전의 값들 (2026-07-17 슬라이스2에서 묶음 — 셋은 항상 같은 사전에서
     * 같이 오고 늘 함께 다닌다. 인자로 흩어 놓으면 호출부마다 셋을 다시 조립해야 한다).
     *
     * @param matchKeys     이름 → 매칭 키. 멤버는 대표 이름을 가리키고, 안 묶인 이름은 자기 이름.
     * @param seasoningNames 양념 이름 집합 — 저장소가 멤버까지 대표 기준으로 펼쳐서 준다.
     * @param basicNames     기본양념 이름 집합 — 같은 방식.
     */
    record Dictionary(Map<String, String> matchKeys, Set<String> seasoningNames, Set<String> basicNames) {

        /** 사전에 없는 이름은 자기 이름 — 안 묶인 것과 같은 취급(엄격 매칭이 안전 기본값) */
        String keyOf(String name) {
            return matchKeys.getOrDefault(name, name);
        }
    }

    /** BASIC = 집에 늘 있는 상비 양념(물·소금·설탕…) — 부족분에서 아예 뺀다. SEASONING(고추장·
        굴소스…)과 달리 "양념만 부족" 섹션에도 안 남는다. 이게 없으면 완전 가능 섹션이 구조적으로
        항상 0이다(레시피 115개 중 48개가 "물"을 재료로 적는데 냉장고엔 물이 없음 — 2026-07-17 실측). */
    enum Tier { MAIN, SEASONING, BASIC }

    static Tier classify(String ingredientName, Dictionary dictionary) {
        if (dictionary.basicNames().contains(ingredientName)) {
            return Tier.BASIC;
        }
        return dictionary.seasoningNames().contains(ingredientName) ? Tier.SEASONING : Tier.MAIN;
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

    /** @param expiringUsed 냉장고 임박(expiring) 재료 중 이 레시피가 쓰는 것의 개수(그룹 키 기준) —
        정렬 가중치. "임박 재료를 쓰는 레시피를 위로"(2026-07-18 확정, 4차 때 아이디어로만 남겼던 것) */
    record Match(Candidate candidate, List<String> missingMain, List<String> missingSeasoning, int expiringUsed) {

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

    /** 냉장고 재료 이름을 매칭 키로 바꾼다 — 호출부가 후보마다 다시 계산하지 않게 한 번만.
        (2026-07-17 슬라이스2 — 이전엔 이름을 그대로 비교했다) */
    static Set<String> keysOf(Set<String> names, Dictionary dictionary) {
        return names.stream().map(dictionary::keyOf).collect(Collectors.toSet());
    }

    /**
     * 매칭 (2026-07-17 슬라이스2 — 이름 정확 일치에서 match_key 비교로 교체).
     * 냉장고의 "계란"과 레시피의 "계란 2개"가 같은 그룹이면 "있음"으로 친다. 안 묶인 이름은
     * 키가 자기 이름이라 예전과 똑같이 동작한다(그룹을 안 쓰면 아무것도 안 바뀜 = 안전).
     * 부족분에 담는 이름은 **레시피 원문 그대로**다 — 화면에 대표 이름("계란")이 아니라 영상이
     * 말한 이름("계란 2개")이 보여야 원문 보존 원칙과 맞다.
     *
     * @param fridgeKeys 냉장고 재료의 매칭 키 집합 (keysOf 로 미리 변환한 것)
     * @param expiringKeys 그중 임박(expiring) 재료의 키 집합 — 이 레시피가 몇 개나 쓰는지 센다(정렬 가중치)
     */
    static Match match(Candidate candidate, Set<String> fridgeKeys, Set<String> expiringKeys,
                       Dictionary dictionary) {
        List<String> missingMain = new ArrayList<>();
        List<String> missingSeasoning = new ArrayList<>();
        Set<String> expiringUsed = new HashSet<>(); // 키 기준 중복 제거 — "계란"과 "계란 2개"는 1개
        for (String raw : candidate.ingredients()) {
            String name = raw == null ? "" : raw.trim();
            if (name.isEmpty()) {
                continue;
            }
            String key = dictionary.keyOf(name);
            if (expiringKeys.contains(key)) {
                expiringUsed.add(key);
            }
            if (fridgeKeys.contains(key)) {
                continue;
            }
            Tier tier = classify(name, dictionary);
            if (tier == Tier.BASIC) {
                continue; // 늘 있다고 간주 — 부족분에 안 셈
            }
            if (tier == Tier.SEASONING) {
                missingSeasoning.add(name);
            } else {
                missingMain.add(name);
            }
        }
        return new Match(candidate, missingMain, missingSeasoning, expiringUsed.size());
    }

    /* ── 2-1. 구매 추천 (2026-07-19 확정 — 냉장고 재료 추가 시트 하단) ── */

    /** "이거 하나만 사면 만들 수 있어요" 한 줄 — name 은 대표(match_key) 이름, recipeTitles 는
        그 재료 하나로 완성되는 내 레시피의 요리 이름들 */
    record ShoppingSuggestion(String name, List<String> recipeTitles) {
    }

    /**
     * 구매 추천 집계: **내 보관함** 레시피 중 주재료가 정확히 1개만 부족한 것을 모은다.
     * 부족 이름은 대표(match_key)로 정규화해 묶는다 — "계란 2개"와 "달걀"이 각각 부족한 두
     * 레시피는 같은 "계란" 한 줄이 된다(부족분 원문 보존 원칙은 레시피 카드 얘기고, 여기는
     * "무엇을 사야 하나"라 장보기 이름[대표]이 맞다). 양념 부족은 안 본다 — 양념만 부족은
     * 이 앱에서 "만들 수 있음"급 취급(별도 섹션)이라 기준을 주재료로 통일.
     * 정렬: 완성되는 레시피 많은 순 → 이름순(결정적).
     */
    static List<ShoppingSuggestion> shoppingSuggestions(List<Match> matches, Set<String> myVideoIds,
                                                        Dictionary dictionary) {
        Map<String, LinkedHashSet<String>> titlesByKey = new LinkedHashMap<>();
        for (Match match : matches) {
            if (!myVideoIds.contains(match.candidate().videoId()) || match.missingMain().size() != 1) {
                continue;
            }
            String key = dictionary.keyOf(match.missingMain().get(0).trim());
            titlesByKey.computeIfAbsent(key, k -> new LinkedHashSet<>())
                    .add(match.candidate().title() == null ? "" : match.candidate().title());
        }
        return titlesByKey.entrySet().stream()
                .map(e -> new ShoppingSuggestion(e.getKey(), List.copyOf(e.getValue())))
                .sorted(Comparator.comparingInt((ShoppingSuggestion s) -> s.recipeTitles().size()).reversed()
                        .thenComparing(ShoppingSuggestion::name))
                .toList();
    }

    /* ── 3. 3단계 판정·정렬 (2026-07-14 4차 확정 · 2026-07-18 정렬 개편 — 사용자 확정) ──
       "목록이 항상 같다" 문제의 해결. 우선순위:
       1) 재료 부족 섹션은 부족 개수 오름차순이 최우선 (기존 유지).
       2) 임박 재료(냉장고 expiring)를 많이 쓰는 레시피 우선 — "완전 매칭·임박 같은 이유로
          항상 보이는 건 OK"라는 요구의 구현.
       3) 그 밖의 동점은 일일 셔플 — 시드(사용자+날짜)가 하루 동안 같아 새로고침·상세 팝업
          복귀에도 목록이 안 튀고, 날이 바뀌면 다른 조합이 올라온다 (매 요청 랜덤은 방금 본
          카드가 사라져 기각 — 2026-07-18 결정). */

    record Sections(List<Match> complete, List<Match> seasoningOnly, List<Match> needsIngredients) {
    }

    /**
     * 완전 가능 / 양념만 부족 / 재료 부족 — 섹션마다 내 보관함 블록(최대 10, 랭킹순) 먼저,
     * 남의 것 블록(최대 10, 랭킹순) 뒤 (2026-07-18 그룹핑 확정 — 처음엔 랭킹 인터리브였는데
     * "내가 저장한 걸 꺼내준다"는 앱 취지와 어긋나 남의 레시피가 맨 앞에 오는 게 실사용에서
     * 혼동을 줬다. 남의 것은 발견용 보조라 뒤가 맞다).
     *
     * @param myVideoIds 내 보관함 videoId 집합 — 블록 분리·상한의 기준
     * @param shuffleSeed 동점 셔플 시드 — 호출부가 사용자+날짜로 만든다 (하루 동안 고정)
     */
    static Sections bucket(List<Match> matches, Set<String> myVideoIds, long shuffleSeed) {
        Comparator<Match> ranking = Comparator
                .comparingInt((Match m) -> -m.expiringUsed())
                .thenComparingInt(m -> shuffleKey(shuffleSeed, m.candidate().videoId()));
        Comparator<Match> byMissingThenRanking = Comparator
                .comparingInt((Match m) -> m.missingMain().size())
                .thenComparing(ranking);
        return new Sections(
                pick(matches.stream().filter(Match::isComplete).sorted(ranking).toList(), myVideoIds),
                pick(matches.stream().filter(Match::isSeasoningOnly).sorted(ranking).toList(), myVideoIds),
                pick(matches.stream().filter(Match::isMissingMain).sorted(byMissingThenRanking).toList(),
                        myVideoIds));
    }

    /** 내 것 블록(랭킹순) + 남의 것 블록(랭킹순) — 각 블록 안의 순서는 랭킹 그대로 */
    private static List<Match> pick(List<Match> ranked, Set<String> myVideoIds) {
        List<Match> mine = new ArrayList<>();
        List<Match> others = new ArrayList<>();
        for (Match match : ranked) {
            if (myVideoIds.contains(match.candidate().videoId())) {
                if (mine.size() < MAX_MINE_PER_SECTION) {
                    mine.add(match);
                }
            } else if (others.size() < MAX_OTHERS_PER_SECTION) {
                others.add(match);
            }
        }
        mine.addAll(others);
        return mine;
    }

    /** 동점 셔플 키 — 같은 시드에선 항상 같은 값(하루 동안 목록 고정), 시드가 바뀌면 뒤섞인다.
        hashCode 를 그대로 쓰면 시드가 더해져도 순서가 거의 안 변해서 곱셈·시프트 믹서로 흩는다. */
    private static int shuffleKey(long seed, String videoId) {
        long h = seed ^ videoId.hashCode() * 0x9E3779B97F4A7C15L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        return Long.hashCode(h);
    }
}
