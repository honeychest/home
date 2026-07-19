// [AGENT] 신규 재료 AI 자동 판정 (2026-07-18 확정 — CONTEXT.md "재료 사전 자동 판정" 절).
// 분석 파이프라인의 마지막 단계: 사전에 남아 있는 PENDING 대표 전부를 AI 감사(IngredientAuditor
// 프롬프트)로 판정해 즉시 적용한다 — 스케줄러·알림 없이 "분석 시점에 바로 결정"(사용자 확정).
// 매 분석마다 전체 PENDING 을 쓸어담으므로(이 영상 것만이 아니라) 한 번 실패해 남은 것도
// 다음 분석 때 자연히 처리된다 — 밀린 것을 따로 챙기는 장치가 필요 없다.
//
// 신뢰 경계 (안전 비대칭 규칙의 자동화 버전):
//   - PENDING 만 바꾼다 — 오너·과거 판정은 절대 안 덮음 (updateStatusIfPending·autoMergeVariant
//     이 SQL 가드로 보장). 잘못된 자동 반영은 그룹 해제·재분류로 완전 복구 가능(병합 가역성).
//   - 적용 내역은 전부 ingredient_change_log 에 남긴다 — 오너의 역할은 사전 승인이 아니라
//     monitor "자동 반영 내역"의 사후 감사다.
//   - 로컬(무료) 우선, 로컬 불가 시 Gemini — 컨트롤러([AI 점검] 버튼, Gemini 우선)와 반대 순서다.
//     여기는 매 RECIPE 분석마다 도는 상시 경로라 Gemini 한도를 아끼는 쪽이 우선이고
//     (RECIPE 는 추출 자체가 로컬로 끝나 Gemini 0회인 경우가 많다), 버튼은 오너가 지금 최고
//     품질을 원해서 누르는 온디맨드 경로라 Gemini 우선이 맞다.
//   - 어떤 실패도 영상 분석을 실패시키지 않는다 — 판정은 부가 작업이라 조용히 건너뛴다
//     (남은 PENDING 은 다음 분석 또는 수동 [AI 점검]이 처리).
package com.chs.springboot.domain.recipe.registration;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class IngredientAutoJudge {

    private static final Logger log = LoggerFactory.getLogger(IngredientAutoJudge.class);

    private final IngredientAuditor gemini;
    private final LocalIngredientAuditor local;
    private final IngredientDictionaryRepository dictionary;
    private final IngredientChangeLogRepository changeLog;

    public IngredientAutoJudge(IngredientAuditor gemini, LocalIngredientAuditor local,
                               IngredientDictionaryRepository dictionary,
                               IngredientChangeLogRepository changeLog) {
        this.gemini = gemini;
        this.local = local;
        this.dictionary = dictionary;
        this.changeLog = changeLog;
    }

    /** 사전의 PENDING 대표 전부를 판정·적용한다. 분석 흐름을 깨지 않도록 예외를 안 던진다. */
    public void judgePending() {
        try {
            List<IngredientDictionaryRepository.Entry> all = dictionary.all();
            List<String> representatives = all.stream()
                    .filter(IngredientAutoJudge::isRepresentative)
                    .map(IngredientDictionaryRepository.Entry::name)
                    .toList();
            List<String> pending = all.stream()
                    .filter(IngredientAutoJudge::isRepresentative)
                    .filter(e -> IngredientDictionaryRepository.STATUS_PENDING.equals(e.status()))
                    .map(IngredientDictionaryRepository.Entry::name)
                    .toList();
            if (pending.isEmpty()) {
                return; // 신규 없음 — LLM 호출 자체를 생략
            }
            apply(applicableProposals(audit(pending, representatives), all));
        } catch (Exception e) {
            log.warn("[gikka] 재료 자동 판정 건너뜀(다음 분석 때 재시도): {}", e.getMessage());
        }
    }

    private List<IngredientAuditor.Proposal> audit(List<String> pending, List<String> representatives) {
        try {
            return local.audit(pending, representatives);
        } catch (LocalRecipeExtractor.LocalUnavailableException e) {
            log.info("[gikka] 로컬 감사 불가 — Gemini 로 폴백: {}", e.getMessage());
            return gemini.audit(pending, representatives);
        }
    }

    /**
     * 적용 가치가 있는 제안만 남긴다 — 순수 판정(IngredientAuditController.isUseful 과 같은 규칙:
     * 사전에 실재하는 대표만, 병합 대상도 실재하는 대표만(체인 방지), 분류는 PENDING 의
     * SEASONING/BASIC 만(MAIN 은 PENDING 과 동작이 같아 무의미)).
     */
    static List<IngredientAuditor.Proposal> applicableProposals(
            List<IngredientAuditor.Proposal> proposals, List<IngredientDictionaryRepository.Entry> all) {
        Map<String, IngredientDictionaryRepository.Entry> byName = all.stream()
                .collect(Collectors.toMap(IngredientDictionaryRepository.Entry::name, Function.identity()));
        return proposals.stream().filter(p -> {
            IngredientDictionaryRepository.Entry entry = byName.get(p.name());
            if (entry == null || !isRepresentative(entry)
                    || !IngredientDictionaryRepository.STATUS_PENDING.equals(entry.status())) {
                return false; // 지어낸 이름·이미 묶인 멤버·이미 판정된 것
            }
            if (p.isMerge()) {
                IngredientDictionaryRepository.Entry target = byName.get(p.mergeInto());
                return target != null && isRepresentative(target); // 없는 대표·체인 방지
            }
            return !IngredientAuditor.TIER_MAIN.equals(p.suggestedTier());
        }).toList();
    }

    private void apply(List<IngredientAuditor.Proposal> proposals) {
        for (IngredientAuditor.Proposal p : proposals) {
            if (p.isMerge()) {
                Optional<String> mergedInto = dictionary.autoMergeVariant(p.name(), p.mergeInto());
                mergedInto.ifPresent(key -> changeLog.append(p.name(),
                        IngredientChangeLogRepository.ACTION_MERGE, p.name(), key,
                        IngredientChangeLogRepository.SOURCE_AUTO_AUDIT));
            } else {
                String status = IngredientAuditor.TIER_BASIC.equals(p.suggestedTier())
                        ? IngredientDictionaryRepository.STATUS_CONFIRMED_BASIC
                        : IngredientDictionaryRepository.STATUS_CONFIRMED_SEASONING;
                if (dictionary.updateStatusIfPending(p.name(), status)) {
                    changeLog.append(p.name(), IngredientChangeLogRepository.ACTION_CLASSIFY,
                            IngredientDictionaryRepository.STATUS_PENDING, status,
                            IngredientChangeLogRepository.SOURCE_AUTO_AUDIT);
                }
            }
        }
    }

    private static boolean isRepresentative(IngredientDictionaryRepository.Entry entry) {
        return entry.name().equals(entry.matchKey());
    }
}
