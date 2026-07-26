// [AGENT] 신규 재료 AI 자동 판정 — 적용 담당 (2026-07-18 확정, CONTEXT.md "재료 사전 자동 판정" 절).
// 분석 파이프라인의 마지막 단계: 사전에 남아 있는 PENDING 대표를 판정해 즉시 적용한다 —
// 스케줄러·알림 없이 "분석 시점에 바로 결정"(사용자 확정). 매 분석마다 전체 PENDING 을 쓸어담으므로
// (이 영상 것만이 아니라) 한 번 실패해 남은 것도 다음 분석 때 자연히 처리된다 — 밀린 것을 따로
// 챙기는 장치가 필요 없다.
//
// 판정 자체(대상 선정·채널 라우팅·제안 검증)는 DictionaryJudge 가 한다 (2026-07-25 점검 — 예전엔
// 그 셋이 여기와 IngredientAuditController 에 한 벌씩 총 두 벌이었고 이미 미세하게 갈라져 있었다).
// 이 클래스에 남은 것은 "적용"과 "실패 정책" 둘뿐이며, 둘 다 오너 경로와 달라서 남은 것이다.
//
// 신뢰 경계 (안전 비대칭 규칙의 자동화 버전):
//   - PENDING 만 바꾼다 — 오너·과거 판정은 절대 안 덮음 (updateStatusIfPending·autoMergeVariant
//     이 SQL 가드로 보장. DictionaryJudge.applicable 이 한 번 더 거른다 — 이중 방어).
//     잘못된 자동 반영은 그룹 해제·재분류로 완전 복구 가능(병합 가역성).
//   - 적용 내역은 전부 ingredient_change_log 에 남긴다 — 오너의 역할은 사전 승인이 아니라
//     monitor "자동 반영 내역"의 사후 감사다.
//   - 로컬(무료) 우선 — 여기는 매 RECIPE 분석마다 도는 상시 경로라 Gemini 한도를 아끼는 쪽이
//     우선이다(RECIPE 는 추출 자체가 로컬로 끝나 Gemini 0회인 경우가 많다). 오너의 [AI 점검]
//     버튼은 반대로 Gemini 우선 — 지금 최고 품질을 원해서 누른 온디맨드 경로이므로.
//   - 어떤 실패도 영상 분석을 실패시키지 않는다 — 판정은 부가 작업이라 조용히 건너뛴다
//     (남은 PENDING 은 다음 분석 또는 수동 [AI 점검]이 처리). 오너 경로가 503 을 내는 것과
//     대비되는 지점이며, 이 차이 때문에 실패 정책은 DictionaryJudge 로 안 올렸다.
//
// 2026-07-26 registration → dictionary 이관. 워커(registration)가 이 클래스를 부르는 방향은
// 그대로 정상이다 — 사전은 registration 의 산출물이므로 registration → dictionary 한 방향.
package com.chs.gikka.dictionary;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class IngredientAutoJudge {

    private static final Logger log = LoggerFactory.getLogger(IngredientAutoJudge.class);

    private final DictionaryJudge judge;
    private final IngredientDictionaryRepository dictionary;
    private final IngredientChangeLogRepository changeLog;

    public IngredientAutoJudge(DictionaryJudge judge, IngredientDictionaryRepository dictionary,
                               IngredientChangeLogRepository changeLog) {
        this.judge = judge;
        this.dictionary = dictionary;
        this.changeLog = changeLog;
    }

    /** 사전의 PENDING 대표 전부를 판정·적용한다. 분석 흐름을 깨지 않도록 예외를 안 던진다. */
    public void judgePending() {
        try {
            apply(judge.propose(DictionaryJudge.Order.LOCAL_FIRST));
        } catch (Exception e) {
            log.warn("[gikka] 재료 자동 판정 건너뜀(다음 분석 때 재시도): {}", e.getMessage());
        }
    }

    /** 묶기는 autoMergeVariant(PENDING·미병합 가드), 분류는 updateStatusIfPending — 실제로 바뀐
        것만 변경 로그에 남긴다(사후 감사가 "안 바뀐 것"으로 오염되지 않게). */
    private void apply(List<IngredientJudge.Proposal> proposals) {
        for (IngredientJudge.Proposal p : proposals) {
            if (p.isMerge()) {
                Optional<String> mergedInto = dictionary.autoMergeVariant(p.name(), p.mergeInto());
                mergedInto.ifPresent(key -> changeLog.append(p.name(),
                        IngredientChangeLogRepository.ACTION_MERGE, p.name(), key,
                        IngredientChangeLogRepository.SOURCE_AUTO_AUDIT));
            } else {
                String status = IngredientJudge.TIER_BASIC.equals(p.suggestedTier())
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
}
