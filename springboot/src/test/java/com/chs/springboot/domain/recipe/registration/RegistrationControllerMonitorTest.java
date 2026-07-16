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

    private RegistrationController controller() {
        GikkaAuthProperties properties = new GikkaAuthProperties();
        properties.setAllowedEmails(List.of("owner@example.com"));
        when(users.findEmail(OWNER_ID)).thenReturn("owner@example.com");
        when(users.findEmail(STRANGER_ID)).thenReturn("stranger@example.com");
        return new RegistrationController(repository, videos, rateLimiter, metadata, properties, users,
                localExtractor);
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
                () -> controller().monitor(STRANGER_ID, 50));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
    }
}
