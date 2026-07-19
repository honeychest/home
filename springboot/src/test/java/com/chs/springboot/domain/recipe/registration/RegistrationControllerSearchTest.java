// [AGENT] 보관함 검색·by-video 등록의 계약 고정 (DB·HTTP 없는 순수 테스트, 2026-07-16 5차).
// 검색 자체의 매칭 SQL 은 저장소 소관이고 여기선 컨트롤러의 위임·상태코드 계약만 잠근다:
//  - by-video: 없는·삭제된 영상=404 / 이미 내 것=409 / 정상=메타 없이 연결만(registerLink Optional.empty).
//  - search: q 가 비면 저장소를 안 부르고 빈 결과 / q 가 있으면 mine·others 를 limit 그대로 조회.
package com.chs.springboot.domain.recipe.registration;

import java.util.List;
import java.util.Optional;

import com.chs.springboot.domain.recipe.auth.GikkaAuthProperties;
import com.chs.springboot.domain.recipe.user.GikkaUserRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegistrationControllerSearchTest {

    private static final long USER_ID = 1L;
    private static final String VIDEO_ID = "aaaaaaaaaaa";

    private final RegistrationRepository repository = mock(RegistrationRepository.class);
    private final VideoRepository videos = mock(VideoRepository.class);
    private final GeminiRateLimiter rateLimiter = mock(GeminiRateLimiter.class);
    private final VideoMetadataClient metadata = mock(VideoMetadataClient.class);
    private final GikkaUserRepository users = mock(GikkaUserRepository.class);
    private final LocalRecipeExtractor localExtractor = mock(LocalRecipeExtractor.class);
    private final IngredientDictionaryRepository dictionary = mock(IngredientDictionaryRepository.class);
    private final IngredientChangeLogRepository changeLog = mock(IngredientChangeLogRepository.class);

    private RegistrationController controller() {
        return new RegistrationController(repository, videos, rateLimiter, metadata,
                new GikkaAuthProperties(), users, localExtractor, dictionary, changeLog);
    }

    @Test
    @DisplayName("by-video: 없는·삭제된 영상이면 404 — 담기를 막고 연결도 안 만든다")
    void byVideoUnavailableIs404() {
        when(videos.existsActive(VIDEO_ID)).thenReturn(false);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller().registerByVideoId(USER_ID, VIDEO_ID));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        verify(repository, never()).registerLink(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("by-video: 이미 내 보관함에 있으면 409")
    void byVideoAlreadyMineIs409() {
        when(videos.existsActive(VIDEO_ID)).thenReturn(true);
        when(repository.exists(USER_ID, VIDEO_ID)).thenReturn(true);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller().registerByVideoId(USER_ID, VIDEO_ID));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        verify(repository, never()).registerLink(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("by-video: 정상이면 메타 재조회 없이 연결만 만든다 (이미 분석된 영상)")
    void byVideoLinksOnly() {
        when(videos.existsActive(VIDEO_ID)).thenReturn(true);
        when(repository.exists(USER_ID, VIDEO_ID)).thenReturn(false);
        when(repository.find(USER_ID, VIDEO_ID)).thenReturn(Optional.empty()); // find 빈 건 이 테스트 관심 밖(500)

        assertThrows(ResponseStatusException.class, () -> controller().registerByVideoId(USER_ID, VIDEO_ID));
        verify(repository).registerLink(USER_ID, VIDEO_ID, Optional.empty());
        verify(metadata, never()).fetchOne(anyString());
    }

    @Test
    @DisplayName("search: q 가 비면 저장소를 안 부르고 빈 결과")
    void searchBlankQueryReturnsEmpty() {
        var result = controller().search(USER_ID, "   ", 10);

        assertTrue(result.mine().isEmpty());
        assertTrue(result.others().isEmpty());
        verify(repository, never()).searchMine(anyLong(), anyString());
        verify(repository, never()).searchOthers(anyLong(), anyString(), anyInt());
    }

    @Test
    @DisplayName("search: q 가 있으면 mine·others 를 limit 그대로 조회한다 (앞뒤 공백은 잘라서)")
    void searchDelegatesTrimmedQueryAndLimit() {
        when(repository.searchMine(USER_ID, "김치")).thenReturn(List.of());
        when(repository.searchOthers(USER_ID, "김치", 10)).thenReturn(List.of());

        var result = controller().search(USER_ID, "  김치  ", 10);

        assertTrue(result.mine().isEmpty());
        assertTrue(result.others().isEmpty());
        verify(repository).searchMine(USER_ID, "김치");
        verify(repository).searchOthers(USER_ID, "김치", 10);
    }
}
