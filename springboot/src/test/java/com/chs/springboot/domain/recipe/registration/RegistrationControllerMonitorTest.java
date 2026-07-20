// [AGENT] 모니터링 화면 오너 전용 강제 동작의 에러 계약 고정 (DB·HTTP 없는 순수 테스트).
// 계기: reanalyze 가 "없는 영상 = false" 를 계산해 반환하는데 컨트롤러가 그 값을 버려서
// 없는 videoId 로도 200 을 주고 아무 일도 안 하던 버그 (2026-07-15 아키텍처 점검에서 발견).
// 컨트롤러 테스트가 하나도 없어 잡히지 않았음 — 에러 계약(403/404)을 여기서 잠근다.
package com.chs.springboot.domain.recipe.registration;

import java.util.List;
import java.util.Optional;

import com.chs.springboot.domain.recipe.auth.GikkaAuthProperties;
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

class RegistrationControllerMonitorTest {

    private static final long OWNER_ID = 1L;
    private static final long STRANGER_ID = 2L;

    private final RegistrationRepository repository = mock(RegistrationRepository.class);
    private final VideoRepository videos = mock(VideoRepository.class);
    private final GeminiRateLimiter rateLimiter = mock(GeminiRateLimiter.class);
    private final VideoMetadataClient metadata = mock(VideoMetadataClient.class);
    private final GikkaUserRepository users = mock(GikkaUserRepository.class);
    /** health() 는 mock 기본값 null 을 돌려준다 = "로컬 못 씀" — 이 테스트의 관심사(404/403 계약)와 무관 */
    private final LocalRecipeExtractor localExtractor = mock(LocalRecipeExtractor.class);
    private final IngredientDictionaryRepository dictionary = mock(IngredientDictionaryRepository.class);
    private final IngredientChangeLogRepository changeLog = mock(IngredientChangeLogRepository.class);

    private RegistrationController controller() {
        GikkaAuthProperties properties = new GikkaAuthProperties();
        properties.setAllowedEmails(List.of("owner@example.com"));
        properties.setOwnerEmail("owner@example.com"); // isOwner() 는 2026-07-20 부터 이 값만 봄
        when(users.findEmail(OWNER_ID)).thenReturn("owner@example.com");
        when(users.findEmail(STRANGER_ID)).thenReturn("stranger@example.com");
        return new RegistrationController(repository, videos, rateLimiter, metadata, properties, users,
                localExtractor, dictionary, changeLog);
    }

    @Test
    @DisplayName("강제 재분석: 없는 영상이면 404 — 저장소의 false 를 삼키지 않는다")
    void reanalyzeMissingVideoIs404() {
        when(metadata.fetchOne(anyString())).thenReturn(Optional.empty());
        when(videos.reanalyze(anyString(), any())).thenReturn(false);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller().monitorReanalyze(OWNER_ID, "nosuchvideo"));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    @DisplayName("강제 재분석: 있는 영상이면 정상 통과")
    void reanalyzeExistingVideoSucceeds() {
        when(metadata.fetchOne(anyString())).thenReturn(Optional.empty());
        when(videos.reanalyze(anyString(), any())).thenReturn(true);

        assertDoesNotThrow(() -> controller().monitorReanalyze(OWNER_ID, "aaaaaaaaaaa"));
    }

    @Test
    @DisplayName("영상 삭제: 없는 영상이면 404 (재분석과 같은 계약)")
    void removeMissingVideoIs404() {
        when(videos.remove(anyString())).thenReturn(false);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller().monitorRemove(OWNER_ID, "nosuchvideo"));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    @DisplayName("영상 삭제: 있는 영상이면 정상 통과")
    void removeExistingVideoSucceeds() {
        when(videos.remove(anyString())).thenReturn(true);

        assertDoesNotThrow(() -> controller().monitorRemove(OWNER_ID, "aaaaaaaaaaa"));
    }

    @Test
    @DisplayName("오너가 아니면 강제 재분석은 403 — 영상에 손대기 전에 막힌다")
    void reanalyzeRequiresOwner() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller().monitorReanalyze(STRANGER_ID, "aaaaaaaaaaa"));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        verify(videos, never()).reanalyze(anyString(), any());
    }

    @Test
    @DisplayName("오너가 아니면 영상 삭제는 403 — 영상에 손대기 전에 막힌다")
    void removeRequiresOwner() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller().monitorRemove(STRANGER_ID, "aaaaaaaaaaa"));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        verify(videos, never()).remove(anyString());
    }

    @Test
    @DisplayName("오너가 아니면 모니터링 조회는 403")
    void monitorRequiresOwner() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller().monitor(STRANGER_ID, 50, null, null));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
    }

    @Test
    @DisplayName("사전 판정: 오너가 아니면 403 — 사전에 손대기 전에 막힌다")
    void classifyRequiresOwner() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller().classifyIngredient(STRANGER_ID,
                        new RegistrationController.ClassifyRequest("간장", "CONFIRMED_SEASONING")));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        verify(dictionary, never()).updateStatus(anyString(), anyString());
    }

    @Test
    @DisplayName("사전 판정: 잘못된 status 는 400 — 저장소를 건드리지 않는다")
    void classifyRejectsBadStatus() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller().classifyIngredient(OWNER_ID,
                        new RegistrationController.ClassifyRequest("간장", "NONSENSE")));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(dictionary, never()).updateStatus(anyString(), anyString());
    }

    @Test
    @DisplayName("사전 판정: 사전에 없는 이름이면 404 — 저장소의 false 를 삼키지 않는다")
    void classifyMissingNameIs404() {
        when(dictionary.updateStatus(anyString(), anyString())).thenReturn(false);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller().classifyIngredient(OWNER_ID,
                        new RegistrationController.ClassifyRequest("없는재료", "CONFIRMED_MAIN")));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    @DisplayName("사전 판정: 오너가 유효한 status 로 있는 이름을 판정하면 정상 통과")
    void classifySucceeds() {
        when(dictionary.updateStatus(anyString(), anyString())).thenReturn(true);

        assertDoesNotThrow(() -> controller().classifyIngredient(OWNER_ID,
                new RegistrationController.ClassifyRequest("굴소스", "CONFIRMED_SEASONING")));
        verify(dictionary).updateStatus("굴소스", "CONFIRMED_SEASONING");
    }

    @Test
    @DisplayName("사전 목록: 오너가 아니면 403")
    void dictionaryReadsRequireOwner() {
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> controller().dictionary(STRANGER_ID)).getStatusCode());
    }

    @Test
    @DisplayName("일괄 판정: 오너가 아니면 403 — 사전에 손대기 전에 막힌다")
    void classifyBatchRequiresOwner() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller().classifyIngredients(STRANGER_ID, List.of(
                        new RegistrationController.ClassifyRequest("간장", "CONFIRMED_BASIC"))));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        verify(dictionary, never()).updateStatuses(any());
    }

    @Test
    @DisplayName("일괄 판정: 한 건이라도 status 가 잘못되면 400 — 저장소를 아예 안 건드린다 "
            + "(일부만 반영되고 실패하면 오너가 무엇이 적용됐는지 알 수 없다)")
    void classifyBatchRejectsBadStatusWholesale() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller().classifyIngredients(OWNER_ID, List.of(
                        new RegistrationController.ClassifyRequest("간장", "CONFIRMED_BASIC"),
                        new RegistrationController.ClassifyRequest("굴소스", "NONSENSE"))));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(dictionary, never()).updateStatuses(any());
    }

    @Test
    @DisplayName("일괄 판정: 없는 이름은 404 가 아니라 조용히 건너뛰고 바뀐 건수를 돌려준다 "
            + "(개별 classify 와 다른 계약 — 제안 83개 중 한 건 때문에 전체를 실패시키지 않는다)")
    void classifyBatchSkipsMissingNames() {
        when(dictionary.updateStatuses(any())).thenReturn(1);

        assertEquals(1, controller().classifyIngredients(OWNER_ID, List.of(
                new RegistrationController.ClassifyRequest("굴소스", "CONFIRMED_SEASONING"),
                new RegistrationController.ClassifyRequest("없는재료", "CONFIRMED_SEASONING"))));
    }
    // AI 점검(auditDictionary)의 계약은 IngredientAuditControllerTest 로 옮겼다 (2026-07-17,
    // 컨트롤러 분리 — /api/recipe/llm/** 전용 경로).
}
