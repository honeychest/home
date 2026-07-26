// [AGENT] 자동 판정의 "적용" 고정 — DB·HTTP 없는 모의 테스트.
// 판정 규칙(대상 선정·라우팅·제안 검증)은 DictionaryJudgeTest 가 잠근다 (2026-07-25 분리).
// 여기 남은 관심사는 둘: 제안을 어떤 사전 쓰기로 옮기는가, 그리고 실패해도 영상 분석을 안 깨뜨리는가.
// 변경 로그는 "실제로 바뀐 것만" 남아야 한다 — 사후 감사가 안 바뀐 것으로 오염되면 오너가 못 믿는다.
package com.chs.gikka.dictionary;

import java.util.List;
import java.util.Optional;

import com.chs.gikka.external.TransientFailureException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngredientAutoJudgeTest {

    private final DictionaryJudge judge = mock(DictionaryJudge.class);
    private final IngredientDictionaryRepository dictionary = mock(IngredientDictionaryRepository.class);
    private final IngredientChangeLogRepository changeLog = mock(IngredientChangeLogRepository.class);

    private final IngredientAutoJudge autoJudge = new IngredientAutoJudge(judge, dictionary, changeLog);

    private void proposes(IngredientJudge.Proposal... proposals) {
        when(judge.propose(DictionaryJudge.Order.LOCAL_FIRST)).thenReturn(List.of(proposals));
    }

    @Test
    @DisplayName("상시 경로는 로컬 우선으로 판정을 요청한다 — Gemini 무료 한도를 아끼는 쪽 "
            + "(오너의 [AI 점검] 버튼은 반대 순서)")
    void asksForLocalFirstOrder() {
        proposes();

        autoJudge.judgePending();

        verify(judge).propose(DictionaryJudge.Order.LOCAL_FIRST);
    }

    @Test
    @DisplayName("BASIC 제안은 CONFIRMED_BASIC, 그 밖의 분류 제안은 CONFIRMED_SEASONING 으로 적용 "
            + "(MAIN 은 애초에 DictionaryJudge 가 걸러 여기 안 온다)")
    void appliesTierProposals() {
        proposes(new IngredientJudge.Proposal("물", IngredientJudge.TIER_BASIC, null),
                new IngredientJudge.Proposal("굴소스", IngredientJudge.TIER_SEASONING, null));
        when(dictionary.updateStatusIfPending(anyString(), anyString())).thenReturn(true);

        autoJudge.judgePending();

        verify(dictionary).updateStatusIfPending("물",
                IngredientDictionaryRepository.STATUS_CONFIRMED_BASIC);
        verify(dictionary).updateStatusIfPending("굴소스",
                IngredientDictionaryRepository.STATUS_CONFIRMED_SEASONING);
        verify(changeLog).append("물", IngredientChangeLogRepository.ACTION_CLASSIFY,
                IngredientDictionaryRepository.STATUS_PENDING,
                IngredientDictionaryRepository.STATUS_CONFIRMED_BASIC,
                IngredientChangeLogRepository.SOURCE_AUTO_AUDIT);
    }

    @Test
    @DisplayName("SQL 가드가 막아 아무것도 안 바뀌면 변경 로그도 안 남긴다 — 사후 감사가 "
            + "'안 바뀐 것'으로 오염되면 안 된다")
    void skipsChangeLogWhenNothingChanged() {
        proposes(new IngredientJudge.Proposal("굴소스", IngredientJudge.TIER_SEASONING, null));
        when(dictionary.updateStatusIfPending(anyString(), anyString())).thenReturn(false);

        autoJudge.judgePending();

        verify(changeLog, never()).append(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("묶기 제안은 autoMergeVariant 로 — 로그에 남는 대표는 평탄화가 반영된 최종 키다 "
            + "(대표가 이미 남의 멤버였으면 그 대표의 대표를 따라간다)")
    void appliesMergeProposalWithFlattenedKey() {
        proposes(new IngredientJudge.Proposal("계란 2개", null, "달걀"));
        when(dictionary.autoMergeVariant("계란 2개", "달걀")).thenReturn(Optional.of("계란"));

        autoJudge.judgePending();

        verify(changeLog).append("계란 2개", IngredientChangeLogRepository.ACTION_MERGE,
                "계란 2개", "계란", IngredientChangeLogRepository.SOURCE_AUTO_AUDIT);
    }

    @Test
    @DisplayName("병합이 안 일어나면(가드에 걸림) 로그도 안 남긴다")
    void skipsChangeLogWhenMergeGuarded() {
        proposes(new IngredientJudge.Proposal("계란 2개", null, "달걀"));
        when(dictionary.autoMergeVariant(anyString(), anyString())).thenReturn(Optional.empty());

        autoJudge.judgePending();

        verify(changeLog, never()).append(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("판정이 실패해도 예외를 안 던진다 — 부가 작업이라 영상 분석을 깨뜨리면 안 된다 "
            + "(남은 PENDING 은 다음 분석이나 수동 [AI 점검]이 처리)")
    void neverBreaksTheAnalysis() {
        when(judge.propose(DictionaryJudge.Order.LOCAL_FIRST))
                .thenThrow(new TransientFailureException("429 quota"));

        assertDoesNotThrow(autoJudge::judgePending);

        verify(dictionary, never()).updateStatusIfPending(anyString(), anyString());
    }
}
