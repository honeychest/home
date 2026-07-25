// [AGENT] 재료 사전 관리 API 의 에러 계약 고정 (DB·HTTP 없는 순수 테스트).
// 오너가 사전에 손대는 유일한 경로라 "누가·무엇을 못 하는가"를 여기서 잠근다: 403(오너 아님) /
// 400(잘못된 status) / 404(사전에 없는 이름). 개별과 일괄의 계약이 일부러 다르다는 것도 함께.
// (구 RegistrationControllerMonitorTest 의 사전 부분 — 2026-07-25 컨트롤러 3분할로 분리)
package com.chs.springboot.domain.recipe.dictionary;

import java.util.List;

import com.chs.springboot.domain.recipe.auth.GikkaAuthProperties;
import com.chs.springboot.domain.recipe.auth.GikkaOwnerGuard;
import com.chs.springboot.domain.recipe.user.GikkaUserRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DictionaryControllerTest {

    private static final long OWNER_ID = 1L;
    private static final long STRANGER_ID = 2L;

    // 2026-07-25 분할 후 협력자는 셋뿐이다 — 예전엔 이 계약 하나를 검증하려고 mock 9개를 세워야 했다
    private final IngredientDictionaryRepository dictionary = mock(IngredientDictionaryRepository.class);
    private final IngredientChangeLogRepository changeLog = mock(IngredientChangeLogRepository.class);
    private final GikkaUserRepository users = mock(GikkaUserRepository.class);

    private DictionaryController controller() {
        GikkaAuthProperties properties = new GikkaAuthProperties();
        properties.setAllowedEmails(List.of("owner@example.com"));
        properties.setOwnerEmail("owner@example.com"); // isOwner() 는 2026-07-20 부터 이 값만 봄
        when(users.findEmail(OWNER_ID)).thenReturn("owner@example.com");
        when(users.findEmail(STRANGER_ID)).thenReturn("stranger@example.com");
        return new DictionaryController(dictionary, changeLog, new GikkaOwnerGuard(properties, users));
    }

    @Test
    @DisplayName("사전 판정: 오너가 아니면 403 — 사전에 손대기 전에 막힌다")
    void classifyRequiresOwner() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller().classifyIngredient(STRANGER_ID,
                        new DictionaryController.ClassifyRequest("간장", "CONFIRMED_SEASONING")));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        verify(dictionary, never()).updateStatus(anyString(), anyString());
    }

    @Test
    @DisplayName("사전 판정: 잘못된 status 는 400 — 저장소를 건드리지 않는다")
    void classifyRejectsBadStatus() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller().classifyIngredient(OWNER_ID,
                        new DictionaryController.ClassifyRequest("간장", "NONSENSE")));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(dictionary, never()).updateStatus(anyString(), anyString());
    }

    @Test
    @DisplayName("사전 판정: 사전에 없는 이름이면 404 — 저장소의 false 를 삼키지 않는다")
    void classifyMissingNameIs404() {
        when(dictionary.updateStatus(anyString(), anyString())).thenReturn(false);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller().classifyIngredient(OWNER_ID,
                        new DictionaryController.ClassifyRequest("없는재료", "CONFIRMED_MAIN")));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    @DisplayName("사전 판정: 오너가 유효한 status 로 있는 이름을 판정하면 정상 통과")
    void classifySucceeds() {
        when(dictionary.updateStatus(anyString(), anyString())).thenReturn(true);

        assertDoesNotThrow(() -> controller().classifyIngredient(OWNER_ID,
                new DictionaryController.ClassifyRequest("굴소스", "CONFIRMED_SEASONING")));
        verify(dictionary).updateStatus("굴소스", "CONFIRMED_SEASONING");
    }

    @Test
    @DisplayName("사전 목록: 오너가 아니면 403")
    void dictionaryReadsRequireOwner() {
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> controller().dictionary(STRANGER_ID)).getStatusCode());
    }

    @Test
    @DisplayName("자동 반영 내역: 오너가 아니면 403")
    void changesRequireOwner() {
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> controller().dictionaryChanges(STRANGER_ID)).getStatusCode());
    }

    @Test
    @DisplayName("자동완성 이름 목록은 오너 게이트가 없다 — 오탈자 예방이 목적이라 모두에게 열려야 "
            + "의미가 있다(이름만 노출, status·그룹 등 관리 정보는 오너 계약에만 있음)")
    void namesAreOpenToEveryLoggedInUser() {
        when(dictionary.representativeNames()).thenReturn(List.of("계란", "두부"));

        assertEquals(List.of("계란", "두부"), controller().dictionaryNames());
    }

    @Test
    @DisplayName("일괄 판정: 오너가 아니면 403 — 사전에 손대기 전에 막힌다")
    void classifyBatchRequiresOwner() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller().classifyIngredients(STRANGER_ID, List.of(
                        new DictionaryController.ClassifyRequest("간장", "CONFIRMED_BASIC"))));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        verify(dictionary, never()).updateStatuses(any());
    }

    @Test
    @DisplayName("일괄 판정: 한 건이라도 status 가 잘못되면 400 — 저장소를 아예 안 건드린다 "
            + "(일부만 반영되고 실패하면 오너가 무엇이 적용됐는지 알 수 없다)")
    void classifyBatchRejectsBadStatusWholesale() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller().classifyIngredients(OWNER_ID, List.of(
                        new DictionaryController.ClassifyRequest("간장", "CONFIRMED_BASIC"),
                        new DictionaryController.ClassifyRequest("굴소스", "NONSENSE"))));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(dictionary, never()).updateStatuses(any());
    }

    @Test
    @DisplayName("일괄 판정: 없는 이름은 404 가 아니라 조용히 건너뛰고 바뀐 건수를 돌려준다 "
            + "(개별 classify 와 다른 계약 — 제안 83개 중 한 건 때문에 전체를 실패시키지 않는다)")
    void classifyBatchSkipsMissingNames() {
        when(dictionary.updateStatuses(any())).thenReturn(1);

        assertEquals(1, controller().classifyIngredients(OWNER_ID, List.of(
                new DictionaryController.ClassifyRequest("굴소스", "CONFIRMED_SEASONING"),
                new DictionaryController.ClassifyRequest("없는재료", "CONFIRMED_SEASONING"))));
    }

    @Test
    @DisplayName("그룹 확정: 오너가 아니면 403 — 묶기는 오너 확정만(안전 비대칭 규칙)")
    void mergeRequiresOwner() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller().mergeIngredient(STRANGER_ID,
                        new DictionaryController.MergeRequest("계란 2개", "계란")));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        verify(dictionary, never()).merge(anyString(), anyString());
    }

    @Test
    @DisplayName("그룹 확정: 사전에 없는 이름·대표면 404 — 저장소의 false 를 삼키지 않는다")
    void mergeMissingNameIs404() {
        when(dictionary.merge(anyString(), anyString())).thenReturn(false);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller().mergeIngredient(OWNER_ID,
                        new DictionaryController.MergeRequest("없는재료", "계란")));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    // AI 점검(auditDictionary)의 계약은 registration/IngredientAuditControllerTest 에 있다 —
    // 동기 LLM 호출이라 전용 경로(/api/recipe/llm/**)를 쓰는 별도 컨트롤러다 (2026-07-17).
}
