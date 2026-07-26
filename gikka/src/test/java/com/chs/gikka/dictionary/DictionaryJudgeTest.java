// [AGENT] 재료 사전 판정 규칙 고정 — DB·HTTP 없는 순수/모의 테스트.
// 2026-07-25 이전엔 같은 규칙이 IngredientAutoJudgeTest 와 IngredientAuditControllerTest 에 한 벌씩,
// 총 두 벌로 검증되고 있었다(그리고 워커 쪽 "판정 대상 선정"은 아무 데서도 검증되지 않았다).
// 규칙이 한 모듈로 모이면서 테스트도 여기 한 곳으로 모은다.
//
// 자동 적용은 사람 확인이 없는 경로라, "무엇을 절대 안 건드리는가"(오너 판정·멤버·지어낸 이름)가
// 이 파일의 핵심이다. 실제 쓰기의 PENDING 가드는 SQL(updateStatusIfPending·autoMergeVariant)이
// 한 번 더 지킨다 — 이중 방어.
package com.chs.gikka.dictionary;

import java.util.List;

import com.chs.gikka.external.LocalUnavailableException;
import com.chs.gikka.external.TransientFailureException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DictionaryJudgeTest {

    private final IngredientDictionaryRepository dictionary = mock(IngredientDictionaryRepository.class);
    private final IngredientAuditor gemini = mock(IngredientAuditor.class);
    private final LocalIngredientAuditor local = mock(LocalIngredientAuditor.class);

    private final DictionaryJudge judge = new DictionaryJudge(dictionary, gemini, local);

    private static IngredientDictionaryRepository.Entry entry(String name, String status) {
        return new IngredientDictionaryRepository.Entry(name, name, status);
    }

    private static IngredientDictionaryRepository.Entry member(String name, String matchKey) {
        return new IngredientDictionaryRepository.Entry(name, matchKey,
                IngredientDictionaryRepository.STATUS_PENDING);
    }

    private static IngredientJudge.Proposal tier(String name, String suggestedTier) {
        return new IngredientJudge.Proposal(name, suggestedTier, null);
    }

    private static IngredientJudge.Proposal merge(String name, String mergeInto) {
        return new IngredientJudge.Proposal(name, null, mergeInto);
    }

    /* ── 판정 대상 선정 ── */

    @Test
    @DisplayName("판정 대상은 PENDING 대표만, 참고용 전체 대표 목록엔 확정된 이름도 포함한다 — "
            + "묶기 제안의 대표 후보가 될 수 있어야 하므로(대표 후보 '라면'이 이미 CONFIRMED_MAIN "
            + "이어도 참고 목록엔 남아 있어야 묶을 곳이 있다)")
    void sendsPendingOnlyWithFullReferenceList() {
        when(dictionary.all()).thenReturn(List.of(
                entry("굴소스", IngredientDictionaryRepository.STATUS_PENDING),
                entry("라면", IngredientDictionaryRepository.STATUS_CONFIRMED_MAIN),
                member("신라면", "라면")));
        when(gemini.audit(anyList(), anyList())).thenReturn(List.of());

        judge.propose(DictionaryJudge.Order.GEMINI_FIRST);

        verify(gemini).audit(List.of("굴소스"), List.of("굴소스", "라면"));
    }

    @Test
    @DisplayName("PENDING 대표가 하나도 없으면 LLM 호출 자체를 생략한다 — 확정 대표끼리의 뒤늦은 "
            + "묶기는 더 이상 제안하지 않는다(안전한 실패 모드로 받아들인 트레이드오프)")
    void skipsLlmWhenNoPendingRepresentatives() {
        when(dictionary.all()).thenReturn(List.of(
                entry("라면", IngredientDictionaryRepository.STATUS_CONFIRMED_MAIN),
                entry("신라면", IngredientDictionaryRepository.STATUS_CONFIRMED_MAIN)));

        assertEquals(List.of(), judge.propose(DictionaryJudge.Order.LOCAL_FIRST));

        verify(gemini, never()).audit(anyList(), anyList());
        verify(local, never()).audit(anyList(), anyList());
    }

    /* ── 채널 라우팅 (순서가 호출부마다 반대인 것이 이 모듈의 존재 이유) ── */

    @Test
    @DisplayName("LOCAL_FIRST 는 로컬부터 — 상시 경로(워커)가 Gemini 무료 한도를 아끼는 방식")
    void localFirstCallsLocalOnly() {
        when(dictionary.all()).thenReturn(List.of(entry("굴소스",
                IngredientDictionaryRepository.STATUS_PENDING)));
        when(local.audit(anyList(), anyList()))
                .thenReturn(List.of(tier("굴소스", IngredientJudge.TIER_SEASONING)));

        List<IngredientJudge.Proposal> proposals = judge.propose(DictionaryJudge.Order.LOCAL_FIRST);

        assertEquals(List.of("굴소스"), proposals.stream().map(IngredientJudge.Proposal::name).toList());
        verify(gemini, never()).audit(anyList(), anyList());
    }

    @Test
    @DisplayName("GEMINI_FIRST 는 Gemini 부터 — 오너가 지금 최고 품질을 원해 누른 온디맨드 경로")
    void geminiFirstCallsGeminiOnly() {
        when(dictionary.all()).thenReturn(List.of(entry("굴소스",
                IngredientDictionaryRepository.STATUS_PENDING)));
        when(gemini.audit(anyList(), anyList()))
                .thenReturn(List.of(tier("굴소스", IngredientJudge.TIER_SEASONING)));

        List<IngredientJudge.Proposal> proposals = judge.propose(DictionaryJudge.Order.GEMINI_FIRST);

        assertEquals(List.of("굴소스"), proposals.stream().map(IngredientJudge.Proposal::name).toList());
        verify(local, never()).audit(anyList(), anyList());
    }

    @Test
    @DisplayName("Gemini 일시적 실패면 로컬로 폴백 — 성공하면 같은 검증을 거쳐 반환한다")
    void geminiTransientFailureFallsBackToLocal() {
        when(dictionary.all()).thenReturn(List.of(entry("굴소스",
                IngredientDictionaryRepository.STATUS_PENDING)));
        when(gemini.audit(anyList(), anyList()))
                .thenThrow(new TransientFailureException("503 overloaded"));
        when(local.audit(List.of("굴소스"), List.of("굴소스")))
                .thenReturn(List.of(tier("굴소스", IngredientJudge.TIER_SEASONING)));

        List<IngredientJudge.Proposal> proposals = judge.propose(DictionaryJudge.Order.GEMINI_FIRST);

        assertEquals(List.of("굴소스"), proposals.stream().map(IngredientJudge.Proposal::name).toList());
    }

    @Test
    @DisplayName("로컬 불가면 Gemini 로 폴백 — 상시 경로가 mac-mini 다운에 멈추지 않게")
    void localUnavailableFallsBackToGemini() {
        when(dictionary.all()).thenReturn(List.of(entry("굴소스",
                IngredientDictionaryRepository.STATUS_PENDING)));
        when(local.audit(anyList(), anyList()))
                .thenThrow(new LocalUnavailableException("service down", null));
        when(gemini.audit(List.of("굴소스"), List.of("굴소스")))
                .thenReturn(List.of(tier("굴소스", IngredientJudge.TIER_BASIC)));

        List<IngredientJudge.Proposal> proposals = judge.propose(DictionaryJudge.Order.LOCAL_FIRST);

        assertEquals(List.of("굴소스"), proposals.stream().map(IngredientJudge.Proposal::name).toList());
    }

    @Test
    @DisplayName("두 채널이 다 막히면 1차 채널의 예외를 그대로 던진다 — 호출부의 에러 계약"
            + "(컨트롤러의 503 매핑)이 그 타입에 걸려 있다")
    void bothChannelsDownRethrowsPrimaryFailure() {
        TransientFailureException primary =
                new TransientFailureException("429 quota");
        when(dictionary.all()).thenReturn(List.of(entry("굴소스",
                IngredientDictionaryRepository.STATUS_PENDING)));
        when(gemini.audit(anyList(), anyList())).thenThrow(primary);
        when(local.audit(anyList(), anyList()))
                .thenThrow(new LocalUnavailableException("service down", null));

        TransientFailureException thrown =
                assertThrows(TransientFailureException.class,
                        () -> judge.propose(DictionaryJudge.Order.GEMINI_FIRST));

        assertSame(primary, thrown);
    }

    /* ── 제안 검증 (순수) ── */

    private static final List<IngredientDictionaryRepository.Entry> DICT = List.of(
            entry("계란", IngredientDictionaryRepository.STATUS_CONFIRMED_MAIN),
            member("달걀", "계란"),                                            // 멤버 (이미 묶임)
            entry("굴소스", IngredientDictionaryRepository.STATUS_PENDING),     // 판정 대상
            entry("새 변형", IngredientDictionaryRepository.STATUS_PENDING),    // 판정 대상
            entry("간장", IngredientDictionaryRepository.STATUS_CONFIRMED_BASIC)); // 오너가 이미 판정

    @Test
    @DisplayName("분류 제안: PENDING 대표의 SEASONING/BASIC 만 남는다 — MAIN 은 PENDING 과 동작이 "
            + "같아 바꿀 게 없다(안전 기본값)")
    void classifyKeepsOnlySeasoningAndBasicOfPending() {
        var proposals = List.of(
                tier("굴소스", IngredientJudge.TIER_SEASONING),
                tier("새 변형", IngredientJudge.TIER_MAIN));

        var applicable = DictionaryJudge.applicable(proposals, DICT);

        assertEquals(List.of("굴소스"), applicable.stream().map(IngredientJudge.Proposal::name).toList());
    }

    @Test
    @DisplayName("오너가 이미 판정한 이름·이미 묶인 멤버·사전에 없는 이름은 절대 안 건드린다 "
            + "(사람 판정 우선 — 안전 비대칭 규칙)")
    void neverTouchesJudgedMembersOrInventedNames() {
        var proposals = List.of(
                tier("간장", IngredientJudge.TIER_SEASONING),      // 오너가 BASIC 으로 확정함
                tier("달걀", IngredientJudge.TIER_SEASONING),      // 이미 묶인 멤버
                tier("지어낸 이름", IngredientJudge.TIER_SEASONING)); // 사전에 없는 이름

        assertTrue(DictionaryJudge.applicable(proposals, DICT).isEmpty());
    }

    @Test
    @DisplayName("묶기 제안: 대상이 실재하는 대표일 때만 — 멤버를 대표로 삼으면 A→B→C 체인이 돼 "
            + "A 와 C 의 키가 달라지고 매칭이 조용히 깨진다(그룹 깊이는 항상 1)")
    void mergeTargetMustBeExistingRepresentative() {
        var proposals = List.of(
                merge("새 변형", "계란"),   // 정상 — 대상이 확정된 대표여도 된다
                merge("굴소스", "달걀"),    // 대상이 멤버 — 체인 방지
                merge("굴소스", "없는대표"));

        var applicable = DictionaryJudge.applicable(proposals, DICT);

        assertEquals(List.of(merge("새 변형", "계란")), applicable);
    }

    @Test
    @DisplayName("묶기 제안도 주체가 PENDING 이어야 한다 — 2026-07-25 통일 지점. 예전엔 컨트롤러 "
            + "쪽만 이 검사를 건너뛰어 두 경로의 규칙이 갈려 있었다(판정 대상이 PENDING 뿐이라 "
            + "도달 불가였을 뿐)")
    void mergeSubjectMustAlsoBePending() {
        assertTrue(DictionaryJudge.applicable(List.of(merge("계란", "굴소스")), DICT).isEmpty());
    }
}
