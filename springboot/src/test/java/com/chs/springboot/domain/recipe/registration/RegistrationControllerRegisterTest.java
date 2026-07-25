// [AGENT] 등록 시점 메타 조회 결과에 따른 차단/허용의 계약 고정 (DB·HTTP 없는 순수 테스트).
// 계기: 비공개·삭제된 영상이 조용히 등록돼 "데이터가 없어 분석 불가"를 DONE 으로 저장하던 문제
// (2026-07-16). ㉠ 없는 영상(비공개·삭제)은 404 로 막고, ㉡ 조회 자체 실패는 관대하게 등록 허용 —
// 이 둘을 뒤섞으면 순간 장애에 멀쩡한 영상이 영구 거부되므로 여기서 갈라 잠근다.
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegistrationControllerRegisterTest {

    private static final long USER_ID = 1L;
    // https://youtu.be/aaaaaaaaaaa — YoutubeVideoId 파서를 통과하는 11자 ID
    private static final String URL = "https://youtu.be/aaaaaaaaaaa";
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

    private RegistrationController.RegisterRequest request() {
        return new RegistrationController.RegisterRequest(URL);
    }

    @Test
    @DisplayName("㉠ 비공개·삭제된 영상(메타 없음)이면 404 — 등록을 막는다")
    void privateVideoIs404() {
        when(repository.exists(USER_ID, VIDEO_ID)).thenReturn(false);
        when(videos.existsActive(VIDEO_ID)).thenReturn(false);
        when(metadata.fetchOne(VIDEO_ID)).thenReturn(Optional.empty()); // 200 인데 items 에 없음

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller().register(USER_ID, request()));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        verify(repository, never()).registerLink(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("㉡ 조회 호출 자체가 실패하면 메타 없이 등록 허용 — 순간 장애에 멀쩡한 영상을 영구 거부하지 않는다")
    void transientFailureStillRegisters() {
        when(repository.exists(USER_ID, VIDEO_ID)).thenReturn(false);
        when(videos.existsActive(VIDEO_ID)).thenReturn(false);
        when(metadata.fetchOne(VIDEO_ID))
                .thenThrow(new VideoMetadataClient.TransientMetadataException("503"));
        when(repository.find(USER_ID, VIDEO_ID)).thenReturn(Optional.empty());

        // find 가 비어 500 이 나지만(이 테스트의 관심 밖), 그 전에 registerLink 가 메타 없이 호출됐어야 한다
        assertThrows(ResponseStatusException.class, () -> controller().register(USER_ID, request()));
        verify(repository).registerLink(USER_ID, VIDEO_ID, Optional.empty());
    }

    @Test
    @DisplayName("이미 video 에 있는 영상은 메타 재조회 없이 연결만 — 다른 사용자가 등록해 둔 경우")
    void existingVideoSkipsFetch() {
        when(repository.exists(USER_ID, VIDEO_ID)).thenReturn(false);
        when(videos.existsActive(VIDEO_ID)).thenReturn(true);
        when(repository.find(USER_ID, VIDEO_ID)).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class, () -> controller().register(USER_ID, request()));
        verify(metadata, never()).fetchOne(anyString());
        verify(repository).registerLink(USER_ID, VIDEO_ID, Optional.empty());
    }

    @Test
    @DisplayName("재생목록: 비공개(메타 없음)·신규 영상은 건너뛰고, 메타 있는 것만 등록한다")
    void playlistSkipsUnavailable() {
        String live = "bbbbbbbbbbb";
        String dead = "ccccccccccc";
        when(metadata.playlistVideoIds(anyString())).thenReturn(List.of(live, dead));
        when(videos.existsActive(anyString())).thenReturn(false);
        when(metadata.fetch(any())).thenReturn(List.of(
                new VideoMetadataClient.VideoMetadata(live, "살아있음", null, 60, null)));
        when(repository.exists(anyLong(), anyString())).thenReturn(false);
        when(repository.registerLink(eq(USER_ID), eq(live), any())).thenReturn(true);

        RegistrationController.RegisterRequest req = new RegistrationController.RegisterRequest(
                "https://www.youtube.com/playlist?list=PLxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
        var result = controller().registerPlaylist(USER_ID, req);

        assertEquals(1, result.get("added")); // live 만 등록, dead 는 건너뜀
        verify(repository, never()).registerLink(eq(USER_ID), eq(dead), any());
    }

    /** 재생목록 상한(2026-07-25 확정) — 열한 번째부터는 조회·등록 자체를 안 한다(잘림).
        11개짜리 목록 중 앞 10개만 처리되는지 확인. */
    @Test
    @DisplayName("재생목록: 비-오너는 10개까지만 등록되고 그 뒤는 통째로 잘린다")
    void playlistCapsNonOwnerAtTen() {
        List<String> ids = elevenVideoIds();
        String eleventh = ids.get(10);
        when(metadata.playlistVideoIds(anyString())).thenReturn(ids);
        when(videos.existsActive(anyString())).thenReturn(false);
        when(metadata.fetch(any())).thenAnswer(inv -> {
            List<String> requested = inv.getArgument(0);
            return requested.stream()
                    .map(id -> new VideoMetadataClient.VideoMetadata(id, "제목", null, 60, null))
                    .toList();
        });
        when(repository.exists(anyLong(), anyString())).thenReturn(false);
        when(repository.registerLink(anyLong(), anyString(), any())).thenReturn(true);

        RegistrationController.RegisterRequest req = new RegistrationController.RegisterRequest(
                "https://www.youtube.com/playlist?list=PLxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
        var result = controller().registerPlaylist(USER_ID, req);

        assertEquals(10, result.get("added"));
        verify(metadata, never()).fetch(eq(List.of(eleventh)));
        verify(repository, never()).registerLink(eq(USER_ID), eq(eleventh), any());
    }

    @Test
    @DisplayName("재생목록: 오너는 상한 없이 11개 전부 등록된다")
    void playlistDoesNotCapOwner() {
        List<String> ids = elevenVideoIds();
        when(metadata.playlistVideoIds(anyString())).thenReturn(ids);
        when(videos.existsActive(anyString())).thenReturn(false);
        when(metadata.fetch(any())).thenAnswer(inv -> {
            List<String> requested = inv.getArgument(0);
            return requested.stream()
                    .map(id -> new VideoMetadataClient.VideoMetadata(id, "제목", null, 60, null))
                    .toList();
        });
        when(repository.exists(anyLong(), anyString())).thenReturn(false);
        when(repository.registerLink(anyLong(), anyString(), any())).thenReturn(true);
        when(users.findEmail(USER_ID)).thenReturn("owner@example.com");
        GikkaAuthProperties ownerProps = new GikkaAuthProperties();
        ownerProps.setOwnerEmail("owner@example.com");
        RegistrationController ownerController = new RegistrationController(repository, videos, rateLimiter,
                metadata, ownerProps, users, localExtractor, dictionary, changeLog);

        RegistrationController.RegisterRequest req = new RegistrationController.RegisterRequest(
                "https://www.youtube.com/playlist?list=PLxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
        var result = ownerController.registerPlaylist(USER_ID, req);

        assertEquals(11, result.get("added"));
    }

    private static List<String> elevenVideoIds() {
        return java.util.stream.IntStream.rangeClosed(1, 11)
                .mapToObj(i -> String.format("vid%08d", i))
                .toList();
    }
}
