// [AGENT] 재료 사전 판정 — "사전을 보고 적용할 가치가 있는 제안 목록을 만든다"의 단일 원본
// (2026-07-25 아키텍처 점검에서 신설). 세 가지를 소유한다: 판정 대상 선정 · 채널 라우팅 · 제안 검증.
//
// 왜 모았나: 이 셋이 워커 경로(IngredientAutoJudge)와 오너 [AI 점검] 경로(IngredientAuditController)에
// 각각 한 벌씩, 총 두 벌로 구현돼 있었다. 두 벌은 이미 미세하게 갈라져 있었다 — 컨트롤러 쪽은
// "주체가 PENDING 인가"를 분류 제안에만 물었고 워커 쪽은 묶기 제안에도 물었다. 판정 대상을
// PENDING 만 보내고 응답 스키마가 이름을 enum 으로 강제해서 도달 불가였을 뿐, 규칙이 둘로 갈린
// 상태 자체가 결함이었다. 통일 방향은 "주체는 반드시 PENDING"(아래 applicable) — 컨트롤러 주석이
// 선언하고 있던 의도("주체[판정 대상]만 PENDING 이면 된다")와 같은 쪽이다.
//
// 여기 없는 것 두 가지 — 청중이 달라서 일부러 남겼다:
//   · 적용(사전 쓰기·변경 로그): 워커만 한다. 오너 경로는 제안을 화면에 보여주고 오너가 고른다.
//   · 실패 정책: 워커는 조용히 삼키고(분석을 안 깨뜨린다) 컨트롤러는 503 으로 매핑한다.
package com.chs.springboot.domain.recipe.registration;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.chs.springboot.domain.recipe.dictionary.IngredientDictionaryRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DictionaryJudge {

    private static final Logger log = LoggerFactory.getLogger(DictionaryJudge.class);

    /**
     * 어느 채널을 먼저 부를까. 추출(HybridRecipeExtractor)처럼 @Primary 빈 하나로 못 묶는 이유가
     * 이것이다 — 순서가 호출부마다 반대다.
     *
     * <p>LOCAL_FIRST = 상시 경로(매 RECIPE 분석 뒤). Gemini 무료 한도를 아끼는 쪽이 우선이고,
     * RECIPE 는 추출 자체가 로컬로 끝나 Gemini 를 아예 안 부르는 경우가 많다.
     * <p>GEMINI_FIRST = 온디맨드 경로(오너가 [AI 점검]을 누른 순간). 지금 최고 품질을 원해서 누른
     * 버튼이라 한도보다 품질이 우선이다.
     */
    public enum Order {
        LOCAL_FIRST, GEMINI_FIRST
    }

    private final IngredientDictionaryRepository dictionary;
    private final IngredientAuditor gemini;
    private final LocalIngredientAuditor local;

    public DictionaryJudge(IngredientDictionaryRepository dictionary, IngredientAuditor gemini,
                           LocalIngredientAuditor local) {
        this.dictionary = dictionary;
        this.gemini = gemini;
        this.local = local;
    }

    /**
     * 사전의 PENDING 대표를 판정해 <b>적용할 가치가 있는 제안만</b> 돌려준다.
     * PENDING 대표가 하나도 없으면 LLM 호출 자체를 생략한다(한도 절약).
     *
     * <p>두 채널이 다 막히면 <b>1차 채널의 예외</b>를 그대로 던진다 — 호출부의 에러 계약이 거기
     * 걸려 있다(GEMINI_FIRST 로 부르는 컨트롤러는 TransientFailureException 을 503 으로 매핑한다).
     */
    public List<IngredientJudge.Proposal> propose(Order order) {
        List<IngredientDictionaryRepository.Entry> all = dictionary.all();
        List<String> pending = pendingRepresentatives(all);
        if (pending.isEmpty()) {
            return List.of(); // 신규 없음 — LLM 호출 자체를 생략
        }
        return applicable(route(order, pending, representatives(all)), all);
    }

    /* ── 1. 판정 대상 선정 (순수) ── */

    /** 대표 = 아무에게도 안 묶인 행(자기 이름이 자기 매칭 키). 멤버는 성격을 대표에서 물려받으므로
        애초에 판정 대상이 아니다. */
    static boolean isRepresentative(IngredientDictionaryRepository.Entry entry) {
        return entry.name().equals(entry.matchKey());
    }

    /** mergeInto 후보로 쓸 참고 목록 — 확정된 대표도 포함해야 묶을 곳이 있다. */
    static List<String> representatives(List<IngredientDictionaryRepository.Entry> all) {
        return all.stream()
                .filter(DictionaryJudge::isRepresentative)
                .map(IngredientDictionaryRepository.Entry::name)
                .toList();
    }

    /** 실제 판정 대상 — 아직 아무도 안 정한 대표만. */
    static List<String> pendingRepresentatives(List<IngredientDictionaryRepository.Entry> all) {
        return all.stream()
                .filter(DictionaryJudge::isRepresentative)
                .filter(e -> IngredientDictionaryRepository.STATUS_PENDING.equals(e.status()))
                .map(IngredientDictionaryRepository.Entry::name)
                .toList();
    }

    /* ── 2. 채널 라우팅 ── */

    private List<IngredientJudge.Proposal> route(Order order, List<String> pending,
                                                 List<String> representatives) {
        IngredientJudge primary = order == Order.LOCAL_FIRST ? local : gemini;
        IngredientJudge secondary = order == Order.LOCAL_FIRST ? gemini : local;
        try {
            return primary.audit(pending, representatives);
        } catch (LocalRecipeExtractor.LocalUnavailableException
                 | RecipeExtractor.TransientFailureException e) {
            log.info("[gikka] 사전 판정 1차 채널({}) 불가 — 다른 채널로 폴백: {}",
                    order, e.getMessage());
            try {
                return secondary.audit(pending, representatives);
            } catch (LocalRecipeExtractor.LocalUnavailableException
                     | RecipeExtractor.TransientFailureException fallbackFailure) {
                log.warn("[gikka] 사전 판정 2차 채널도 불가: {}", fallbackFailure.getMessage());
                e.addSuppressed(fallbackFailure);
                throw e; // 1차 예외를 그대로 — 호출부(503 매핑)가 이 타입을 본다
            }
        }
    }

    /* ── 3. 제안 검증 (순수) ── */

    /**
     * 사전 사실과 대조해 실제로 적용할 값이 있는 제안만 남긴다 — 모델은 사전을 모르고 없는 이름을
     * 지어낼 수 있다. 셋을 본다:
     * <ul>
     *   <li>주체가 사전에 실재하는 <b>대표</b>이고 아직 <b>PENDING</b> 인가 — 오너·과거 판정 불가침
     *       (사람 판정 우선). 이미 묶인 멤버도 제외(그 성격은 대표가 정한다).</li>
     *   <li>묶기 제안이면 대상이 실재하는 <b>대표</b>인가 — 멤버를 대표로 삼으면 A→B→C 체인이 돼
     *       A 와 C 의 키가 달라지고 매칭이 조용히 깨진다(그룹 깊이는 항상 1).</li>
     *   <li>분류 제안이면 MAIN 이 아닌가 — PENDING 이 이미 주재료 취급이라 MAIN 은 바꿀 게 없다.</li>
     * </ul>
     * 실제 쓰기의 PENDING 가드는 SQL(updateStatusIfPending·autoMergeVariant)이 한 번 더 지킨다 — 이중 방어.
     */
    static List<IngredientJudge.Proposal> applicable(
            List<IngredientJudge.Proposal> proposals, List<IngredientDictionaryRepository.Entry> all) {
        Map<String, IngredientDictionaryRepository.Entry> byName = all.stream()
                .collect(Collectors.toMap(IngredientDictionaryRepository.Entry::name, Function.identity()));
        return proposals.stream().filter(p -> {
            IngredientDictionaryRepository.Entry entry = byName.get(p.name());
            if (entry == null || !isRepresentative(entry)
                    || !IngredientDictionaryRepository.STATUS_PENDING.equals(entry.status())) {
                return false; // 지어낸 이름 · 이미 묶인 멤버 · 이미 판정된 것
            }
            if (p.isMerge()) {
                IngredientDictionaryRepository.Entry target = byName.get(p.mergeInto());
                return target != null && isRepresentative(target); // 없는 대표 · 체인 방지
            }
            return !IngredientJudge.TIER_MAIN.equals(p.suggestedTier());
        }).toList();
    }
}
