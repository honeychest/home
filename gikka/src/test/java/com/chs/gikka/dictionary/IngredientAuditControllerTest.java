// [AGENT] AI 점검(동기 LLM 호출) 경로의 계약 고정 — DB·HTTP 없는 모의 테스트.
// 판정 규칙(대상 선정·라우팅·제안 검증)은 DictionaryJudgeTest 가 잠근다 (2026-07-25 분리 —
// 예전엔 같은 규칙이 여기와 IngredientAutoJudgeTest 에 두 벌로 검증되고 있었다).
// 여기 남은 관심사는 이 경로만의 계약 둘이다.
//   · 403: 오너가 아니면 LLM 을 부르기 전에 막힌다(한도 소모 0).
//   · 503: 두 채널이 다 막힌 일시적 실패는 500 이 아니라 503 — 감사기는 워커와 달리 재시도
//     정책이 없어 그대로 올라오면 500 이 나가고, 프론트가 "잠시 후 다시"를 못 띄운다
//     (2026-07-17. 이때 화면에 뜬 오류의 진짜 원인은 nginx 15초 타임아웃이었지만 이 구멍도 실재했다).
package com.chs.gikka.dictionary;

import java.util.List;

import com.chs.gikka.auth.GikkaAuthProperties;
import com.chs.gikka.auth.GikkaOwnerGuard;
import com.chs.gikka.external.TransientFailureException;
import com.chs.gikka.user.GikkaUserRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngredientAuditControllerTest {

    private static final long OWNER_ID = 1L;
    private static final long STRANGER_ID = 2L;

    private final DictionaryJudge judge = mock(DictionaryJudge.class);
    private final GikkaUserRepository users = mock(GikkaUserRepository.class);

    private IngredientAuditController controller() {
        GikkaAuthProperties properties = new GikkaAuthProperties();
        properties.setAllowedEmails(List.of("owner@example.com"));
        properties.setOwnerEmail("owner@example.com"); // isOwner() 는 2026-07-20 부터 이 값만 봄
        when(users.findEmail(OWNER_ID)).thenReturn("owner@example.com");
        when(users.findEmail(STRANGER_ID)).thenReturn("stranger@example.com");
        return new IngredientAuditController(judge, new GikkaOwnerGuard(properties, users));
    }

    @Test
    @DisplayName("오너가 아니면 403 — LLM 을 부르기 전에 막힌다 (한도 소모 0)")
    void requiresOwner() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller().auditDictionary(STRANGER_ID));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        verify(judge, never()).propose(any());
    }

    @Test
    @DisplayName("온디맨드 경로는 Gemini 우선으로 판정을 요청한다 — 오너가 지금 최고 품질을 "
            + "원해서 누른 버튼이라 한도보다 품질이 우선 (상시 경로인 워커는 반대 순서)")
    void asksForGeminiFirstOrder() {
        when(judge.propose(DictionaryJudge.Order.GEMINI_FIRST)).thenReturn(List.of());

        assertEquals(List.of(), controller().auditDictionary(OWNER_ID));

        verify(judge).propose(DictionaryJudge.Order.GEMINI_FIRST);
    }

    @Test
    @DisplayName("두 채널이 다 막힌 일시적 실패는 503 — 500 으로 새지 않는다")
    void transientFailureBecomes503() {
        when(judge.propose(DictionaryJudge.Order.GEMINI_FIRST))
                .thenThrow(new TransientFailureException("429 quota"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller().auditDictionary(OWNER_ID));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.getStatusCode());
    }

    @Test
    @DisplayName("제안은 그대로 통과시킨다 — 거르는 책임은 DictionaryJudge 에 있고 여기서 또 거르면 "
            + "규칙이 다시 두 벌이 된다")
    void passesProposalsThrough() {
        List<IngredientJudge.Proposal> proposals =
                List.of(new IngredientJudge.Proposal("굴소스", IngredientJudge.TIER_SEASONING, null));
        when(judge.propose(DictionaryJudge.Order.GEMINI_FIRST)).thenReturn(proposals);

        assertEquals(proposals, controller().auditDictionary(OWNER_ID));
    }
}
