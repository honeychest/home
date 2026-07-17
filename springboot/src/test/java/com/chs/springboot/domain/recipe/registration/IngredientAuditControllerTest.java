// [AGENT] AI 점검(동기 LLM 호출)의 에러 계약 고정 (DB·HTTP 없는 순수 테스트).
// 503 계약의 계기: 감사기는 워커와 달리 재시도 정책이 없어 일시적 실패가 그대로 컨트롤러까지
// 올라가 500 이 나갔다 — 프론트가 "잠시 후 다시"를 띄우려면 상태 코드로 구분돼야 한다
// (2026-07-17. 이때 실제로 화면에 뜬 오류의 원인은 다른 것[nginx 15초 타임아웃]이었지만,
// 이 구멍 자체는 실재해 함께 막았다).
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
    private final GikkaUserRepository users = mock(GikkaUserRepository.class);

    private IngredientAuditController controller() {
        GikkaAuthProperties properties = new GikkaAuthProperties();
        properties.setAllowedEmails(List.of("owner@example.com"));
        when(users.findEmail(OWNER_ID)).thenReturn("owner@example.com");
        when(users.findEmail(STRANGER_ID)).thenReturn("stranger@example.com");
        return new IngredientAuditController(dictionary, auditor, properties, users);
    }

    @Test
    @DisplayName("오너가 아니면 403 — LLM 을 부르기 전에 막힌다 (한도 소모 0)")
    void requiresOwner() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller().auditDictionary(STRANGER_ID));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        verify(auditor, never()).audit(anyList());
    }

    @Test
    @DisplayName("일시적 실패(429·503·타임아웃)는 503 — 500 으로 새지 않는다")
    void transientFailureBecomes503() {
        when(dictionary.all()).thenReturn(List.of(new IngredientDictionaryRepository.Entry(
                "굴소스", "굴소스", IngredientDictionaryRepository.STATUS_PENDING)));
        when(auditor.audit(anyList()))
                .thenThrow(new RecipeExtractor.TransientFailureException("429 quota"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller().auditDictionary(OWNER_ID));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.getStatusCode());
    }

    @Test
    @DisplayName("PENDING 만 LLM 에 보내고, 양념·기본양념 제안만 돌려준다 — MAIN 제안은 버린다 "
            + "(PENDING 이 이미 주재료 취급이라 바꿀 게 없음)")
    void sendsOnlyPendingAndDropsMainProposals() {
        when(dictionary.all()).thenReturn(List.of(
                new IngredientDictionaryRepository.Entry("굴소스", "굴소스",
                        IngredientDictionaryRepository.STATUS_PENDING),
                new IngredientDictionaryRepository.Entry("물", "물",
                        IngredientDictionaryRepository.STATUS_PENDING),
                new IngredientDictionaryRepository.Entry("두부", "두부",
                        IngredientDictionaryRepository.STATUS_PENDING),
                new IngredientDictionaryRepository.Entry("간장", "간장",
                        IngredientDictionaryRepository.STATUS_CONFIRMED_BASIC)));
        when(auditor.audit(List.of("굴소스", "물", "두부"))).thenReturn(List.of(
                new IngredientAuditor.Proposal("굴소스", IngredientAuditor.TIER_SEASONING),
                new IngredientAuditor.Proposal("물", IngredientAuditor.TIER_BASIC),
                new IngredientAuditor.Proposal("두부", IngredientAuditor.TIER_MAIN)));

        List<IngredientAuditor.Proposal> proposals = controller().auditDictionary(OWNER_ID);

        assertEquals(2, proposals.size());
        assertEquals("굴소스", proposals.get(0).name());
        assertEquals("물", proposals.get(1).name());
    }
}
