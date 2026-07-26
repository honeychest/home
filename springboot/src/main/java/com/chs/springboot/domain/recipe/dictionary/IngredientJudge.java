// [AGENT] 재료 사전 판정 시임 (2026-07-25 아키텍처 점검에서 신설) — "재료 이름 목록을 보고
// 분류(양념 여부)·묶기를 제안한다"를 어댑터 둘이 구현한다: IngredientAuditor(Gemini) /
// LocalIngredientAuditor(mac-mini LM Studio).
//
// 추출(RecipeExtractor + @Primary HybridRecipeExtractor)과 달리 여기엔 @Primary 라우터 빈이 없다 —
// 부르는 순서가 하나가 아니기 때문이다. 상시 경로(워커)는 무료 한도를 아끼려 로컬 우선,
// 온디맨드 경로(오너 [AI 점검])는 지금 최고 품질을 원해 누른 버튼이라 Gemini 우선이다.
// 그래서 순서는 DictionaryJudge 가 인자로 받아 라우팅한다.
//
// 제안의 어휘(Proposal·TIER_*)와 두 어댑터가 공유하는 응답 형식(parse)은 이 시임이 소유한다.
// 예전엔 Gemini 구현체(IngredientAuditor)가 들고 있어서 로컬 어댑터가 남의 구현체를 들여다봤다 —
// 시임의 어휘를 구현체 하나가 소유하면 그 구현체를 못 빼낸다.
//
// 2026-07-26 registration → dictionary 이관: 사전 규칙이 사전 패키지 밖에 있던 마지막 덩어리였다.
// 막고 있던 것은 예외 두 개(추출 클래스의 중첩)였고, 그것들이 external 중립 지대로 나오면서 풀렸다.
package com.chs.springboot.domain.recipe.dictionary;

import java.util.ArrayList;
import java.util.List;

import com.chs.springboot.domain.recipe.external.LocalUnavailableException;
import com.chs.springboot.domain.recipe.external.TransientFailureException;
import com.fasterxml.jackson.databind.JsonNode;

public interface IngredientJudge {

    /**
     * pendingNames 만 판정한다. allRepresentatives 는 mergeInto 후보를 고르기 위한 <b>참고 자료</b>일
     * 뿐 판정 대상이 아니다 — 응답 크기가 사전 전체가 아니라 신규(PENDING) 개수에만 비례하게 만드는
     * 핵심이다 (2026-07-18. 예전엔 대표 전체[실측 243개]를 판정 대상으로 보내 사전이 커질수록 출력이
     * 자라다가 gikka-local max_tokens 를 넘겨 503 이 재발했다).
     *
     * <p>구현체는 자기 채널이 지금 불가하면 예외를 던진다 — Gemini 는 일시적 실패에
     * {@link TransientFailureException}, 로컬은 {@link LocalUnavailableException}.
     * 그 둘을 보고 다른 쪽으로 넘기는 것이 DictionaryJudge 의 라우팅이다.
     */
    List<Proposal> audit(List<String> pendingNames, List<String> allRepresentatives);

    /** LLM 이 제시한 분류 — 안전 기본값이자 "제안할 게 없음"을 뜻하는 MAIN 포함 */
    String TIER_MAIN = "MAIN";
    String TIER_SEASONING = "SEASONING";
    String TIER_BASIC = "BASIC";

    /**
     * 오너가 확인할 제안 한 건 — 자동 반영 아님(안전 비대칭 원칙).
     *
     * @param suggestedTier 분류 제안. 묶기 제안이면 null — 묶이는 순간 양념 여부는 대표가 정하므로
     *                      멤버의 tier 를 따로 제안할 이유가 없다.
     * @param mergeInto     묶기 제안(이 이름을 흡수할 대표). 없으면 null.
     */
    record Proposal(String name, String suggestedTier, String mergeInto) {

        public boolean isMerge() {
            return mergeInto != null;
        }
    }

    /**
     * 알맹이 JSON(제안 배열)을 제안 목록으로 — 순수(HTTP·봉투 없음. Gemini 는 GeminiJsonClient 이,
     * 로컬은 호스트 서비스가 이미 봉투를 벗겨서 준다). 두 채널의 응답이 같은 배열 모양이라 시임이 소유한다.
     *
     * <p>모르는 tier 값은 전부 MAIN 으로 정규화한다(안전 기본값 — 스키마가 enum 을 강제하지만 모델이
     * 어긴 경우에도 위험한 쪽으로 기울지 않게). 묶기 제안이면 tier 는 버린다 — 멤버의 양념 여부는
     * 대표가 정하므로 둘을 같이 제안하면 오너가 뭘 승인하는 건지 흐려진다. 자기 자신에게 묶으라는
     * 답(mergeInto == name)은 "안 묶음"과 같은 뜻이라 그렇게 정규화한다.
     *
     * <p>제안이 사전에 실재하는 이름인지·대표 자격이 있는지는 여기서 안 본다 — 그건 사전을 아는
     * DictionaryJudge 의 몫이다(어댑터도 이 파싱도 사전을 모른다).
     */
    static List<Proposal> parse(JsonNode array) {
        List<Proposal> proposals = new ArrayList<>();
        for (JsonNode node : array) {
            String name = node.path("name").asText("").trim();
            if (name.isEmpty()) {
                continue;
            }
            String mergeInto = node.path("mergeInto").asText("").trim();
            if (!mergeInto.isEmpty() && !mergeInto.equals(name)) {
                proposals.add(new Proposal(name, null, mergeInto));
                continue;
            }
            String tier = node.path("tier").asText(TIER_MAIN);
            boolean known = TIER_SEASONING.equals(tier) || TIER_BASIC.equals(tier);
            proposals.add(new Proposal(name, known ? tier : TIER_MAIN, null));
        }
        return proposals;
    }
}
