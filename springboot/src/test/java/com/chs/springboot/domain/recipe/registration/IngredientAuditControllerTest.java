// [AGENT] AI 점검(동기 LLM 호출)의 에러 계약 고정 (DB·HTTP 없는 순수 테스트).
// 503 계약의 계기: 감사기는 워커와 달리 재시도 정책이 없어 일시적 실패가 그대로 컨트롤러까지
// 올라가 500 이 나갔다 — 프론트가 "잠시 후 다시"를 띄우려면 상태 코드로 구분돼야 한다
// (2026-07-17. 이때 실제로 화면에 뜬 오류의 원인은 다른 것[nginx 15초 타임아웃]이었지만,
// 이 구멍 자체는 실재해 함께 막았다).
// 판정 대상 = PENDING 대표만, 전체 대표 목록은 참고 자료(2026-07-18) — 사전이 커질수록 응답이
// 자라 max_tokens 를 넘기던 문제(gikka-local 503)의 근본 수정. 트레이드오프(확정 대표끼리의
// 뒤늦은 묶기 제안 소실)는 skipsLlmWhenNoPendingRepresentatives 가 고정한다.
package com.chs.springboot.domain.recipe.registration;

import java.util.List;

import com.chs.springboot.domain.recipe.auth.GikkaAuthProperties;
import com.chs.springboot.domain.recipe.user.GikkaUserRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngredientAuditControllerTest {

    private static final long OWNER_ID = 1L;
    private static final long STRANGER_ID = 2L;

    private final IngredientDictionaryRepository dictionary = mock(IngredientDictionaryRepository.class);
    private final IngredientAuditor auditor = mock(IngredientAuditor.class);
    private final LocalIngredientAuditor localAuditor = mock(LocalIngredientAuditor.class);
    private final GikkaUserRepository users = mock(GikkaUserRepository.class);

    private IngredientAuditController controller() {
        GikkaAuthProperties properties = new GikkaAuthProperties();
        properties.setAllowedEmails(List.of("owner@example.com"));
        properties.setOwnerEmail("owner@example.com"); // isOwner() 는 2026-07-20 부터 이 값만 봄
        when(users.findEmail(OWNER_ID)).thenReturn("owner@example.com");
        when(users.findEmail(STRANGER_ID)).thenReturn("stranger@example.com");
        return new IngredientAuditController(dictionary, auditor, localAuditor, properties, users);
    }

    @Test
    @DisplayName("오너가 아니면 403 — LLM 을 부르기 전에 막힌다 (한도 소모 0)")
    void requiresOwner() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller().auditDictionary(STRANGER_ID));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        verify(auditor, never()).audit(anyList(), anyList());
    }

    @Test
    @DisplayName("PENDING 대표가 하나도 없으면 LLM 호출 자체를 생략한다 — 확정 대표끼리의 뒤늦은 "
            + "묶기는 더 이상 제안하지 않는다(안전한 실패 모드로 받아들인 트레이드오프)")
    void skipsLlmWhenNoPendingRepresentatives() {
        when(dictionary.all()).thenReturn(List.of(
                entry("라면", IngredientDictionaryRepository.STATUS_CONFIRMED_MAIN),
                entry("신라면", IngredientDictionaryRepository.STATUS_CONFIRMED_MAIN)));

        List<IngredientAuditor.Proposal> proposals = controller().auditDictionary(OWNER_ID);

        assertEquals(List.of(), proposals);
        verify(auditor, never()).audit(anyList(), anyList());
        verify(localAuditor, never()).audit(anyList(), anyList());
    }

    @Test
    @DisplayName("Gemini 일시적 실패(429·503·타임아웃)에 로컬도 안 되면 503 — 500 으로 새지 않는다")
    void transientFailureBecomes503WhenLocalAlsoUnavailable() {
        when(dictionary.all()).thenReturn(List.of(new IngredientDictionaryRepository.Entry(
                "굴소스", "굴소스", IngredientDictionaryRepository.STATUS_PENDING)));
        when(auditor.audit(anyList(), anyList()))
                .thenThrow(new RecipeExtractor.TransientFailureException("429 quota"));
        when(localAuditor.audit(anyList(), anyList()))
                .thenThrow(new LocalRecipeExtractor.LocalUnavailableException("service down", null));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller().auditDictionary(OWNER_ID));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.getStatusCode());
    }

    @Test
    @DisplayName("Gemini 일시적 실패면 로컬(gikka-local)로 폴백 — 성공하면 같은 필터를 거쳐 반환한다")
    void transientFailureFallsBackToLocal() {
        when(dictionary.all()).thenReturn(List.of(
                entry("굴소스", IngredientDictionaryRepository.STATUS_PENDING)));
        when(auditor.audit(anyList(), anyList()))
                .thenThrow(new RecipeExtractor.TransientFailureException("503 overloaded"));
        when(localAuditor.audit(List.of("굴소스"), List.of("굴소스")))
                .thenReturn(List.of(tier("굴소스", IngredientAuditor.TIER_SEASONING)));

        List<IngredientAuditor.Proposal> proposals = controller().auditDictionary(OWNER_ID);

        assertEquals(List.of("굴소스"), proposals.stream().map(IngredientAuditor.Proposal::name).toList());
    }

    private static IngredientDictionaryRepository.Entry entry(String name, String status) {
        return new IngredientDictionaryRepository.Entry(name, name, status);
    }

    private static IngredientDictionaryRepository.Entry member(String name, String matchKey) {
        return new IngredientDictionaryRepository.Entry(name, matchKey,
                IngredientDictionaryRepository.STATUS_PENDING);
    }

    private static IngredientAuditor.Proposal tier(String name, String suggestedTier) {
        return new IngredientAuditor.Proposal(name, suggestedTier, null);
    }

    private static IngredientAuditor.Proposal merge(String name, String mergeInto) {
        return new IngredientAuditor.Proposal(name, null, mergeInto);
    }

    @Test
    @DisplayName("판정 대상은 PENDING 대표만, 참고용 전체 대표 목록엔 확정된 이름도 포함한다 — "
            + "묶기 제안의 대표 후보가 될 수 있어야 하므로(대표 후보 '라면'이 이미 CONFIRMED_MAIN "
            + "이어도 참고 목록엔 남아 있어야 묶을 곳이 있다)")
    void sendsPendingOnlyWithFullReferenceList() {
        when(dictionary.all()).thenReturn(List.of(
                entry("굴소스", IngredientDictionaryRepository.STATUS_PENDING),
                entry("라면", IngredientDictionaryRepository.STATUS_CONFIRMED_MAIN),
                member("신라면", "라면")));
        when(auditor.audit(List.of("굴소스"), List.of("굴소스", "라면"))).thenReturn(List.of());

        controller().auditDictionary(OWNER_ID);

        verify(auditor).audit(List.of("굴소스"), List.of("굴소스", "라면"));
    }

    @Test
    @DisplayName("분류 제안은 PENDING 인 것만, MAIN 제안은 버린다 — PENDING 이 이미 주재료 취급이라 "
            + "바꿀 게 없고, 오너가 이미 정한 건 덮어쓸 후보로 올리지 않는다(사람 판정 우선)")
    void keepsOnlyUsefulTierProposals() {
        when(dictionary.all()).thenReturn(List.of(
                entry("굴소스", IngredientDictionaryRepository.STATUS_PENDING),
                entry("물", IngredientDictionaryRepository.STATUS_PENDING),
                entry("두부", IngredientDictionaryRepository.STATUS_PENDING),
                entry("간장", IngredientDictionaryRepository.STATUS_CONFIRMED_BASIC)));
        when(auditor.audit(anyList(), anyList())).thenReturn(List.of(
                tier("굴소스", IngredientAuditor.TIER_SEASONING),
                tier("물", IngredientAuditor.TIER_BASIC),
                tier("두부", IngredientAuditor.TIER_MAIN),          // 안전 기본값 — 바꿀 게 없다
                tier("간장", IngredientAuditor.TIER_SEASONING)));   // 오너가 이미 BASIC 으로 확정함

        List<IngredientAuditor.Proposal> proposals = controller().auditDictionary(OWNER_ID);

        assertEquals(List.of("굴소스", "물"), proposals.stream().map(IngredientAuditor.Proposal::name).toList());
    }

    @Test
    @DisplayName("묶기 제안의 대상(mergeInto)은 확정된 대표여도 된다 — 주체(판정 대상)만 PENDING 이면 된다")
    void mergeTargetCanBeConfirmed() {
        when(dictionary.all()).thenReturn(List.of(
                entry("라면", IngredientDictionaryRepository.STATUS_CONFIRMED_MAIN),
                entry("신라면", IngredientDictionaryRepository.STATUS_PENDING)));
        when(auditor.audit(List.of("신라면"), List.of("라면", "신라면")))
                .thenReturn(List.of(merge("신라면", "라면")));

        List<IngredientAuditor.Proposal> proposals = controller().auditDictionary(OWNER_ID);

        assertEquals(List.of(merge("신라면", "라면")), proposals);
    }

    @Test
    @DisplayName("모델이 지어낸 이름·사전에 없는 대표는 버린다 — LLM 은 사전을 모른다")
    void dropsProposalsAboutUnknownNames() {
        when(dictionary.all()).thenReturn(List.of(
                entry("라면", IngredientDictionaryRepository.STATUS_PENDING)));
        when(auditor.audit(anyList(), anyList())).thenReturn(List.of(
                tier("없는재료", IngredientAuditor.TIER_SEASONING), // 사전에 없는 이름
                merge("라면", "없는대표")));                        // 사전에 없는 대표

        assertEquals(List.of(), controller().auditDictionary(OWNER_ID));
    }

    @Test
    @DisplayName("이미 묶인 멤버를 대표로 삼자는 제안은 버린다 — A→B→C 체인이 되면 A 와 C 의 키가 "
            + "달라져 매칭이 조용히 깨진다(그룹 깊이는 항상 1)")
    void dropsMergeIntoMember() {
        when(dictionary.all()).thenReturn(List.of(
                entry("라면", IngredientDictionaryRepository.STATUS_CONFIRMED_MAIN),
                member("신라면", "라면"),
                entry("사각라면", IngredientDictionaryRepository.STATUS_PENDING)));
        when(auditor.audit(anyList(), anyList())).thenReturn(List.of(merge("사각라면", "신라면")));

        assertEquals(List.of(), controller().auditDictionary(OWNER_ID));
    }
}
