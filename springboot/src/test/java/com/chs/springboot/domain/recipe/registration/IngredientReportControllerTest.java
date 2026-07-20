// [AGENT] 재료 신고 API 계약 고정 — 공개 전 게이트(403)·검증(400/404)·접수 응답 모양.
// 게이트는 임시지만 공개 전까지는 계약이다 — 실수로 풀리면 아무나 Gemini 재분석을 유발할 수 있음.
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IngredientReportControllerTest {

    private static final long OWNER_ID = 1L;
    private static final long STRANGER_ID = 2L;
    private static final String VIDEO_ID = "aaaaaaaaaaa";

    private final IngredientReportRepository reports = mock(IngredientReportRepository.class);
    private final VideoRepository videos = mock(VideoRepository.class);
    private final GikkaUserRepository users = mock(GikkaUserRepository.class);

    private IngredientReportController controller() {
        GikkaAuthProperties properties = new GikkaAuthProperties();
        properties.setAllowedEmails(List.of("owner@example.com"));
        properties.setOwnerEmail("owner@example.com"); // isOwner() 는 2026-07-20 부터 이 값만 봄
        when(users.findEmail(OWNER_ID)).thenReturn("owner@example.com");
        when(users.findEmail(STRANGER_ID)).thenReturn("stranger@example.com");
        return new IngredientReportController(reports, videos, properties, users);
    }

    private static IngredientReportController.ReportRequest request(String name) {
        return new IngredientReportController.ReportRequest(VIDEO_ID, name);
    }

    @Test
    @DisplayName("공개 전 게이트: 허용 목록 밖 사용자는 접수·조회 모두 403")
    void gatedForNonOwner() {
        var controller = controller();

        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> controller.report(STRANGER_ID, request("쭈유"))).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> controller.myActiveReports(STRANGER_ID, VIDEO_ID)).getStatusCode());
    }

    @Test
    @DisplayName("접수: 신규면 reported, 이미 접수돼 있으면 already — 1인 1신고는 저장소 UNIQUE 가 판정")
    void reportOutcomeReflectsRepository() {
        when(videos.existsActive(VIDEO_ID)).thenReturn(true);
        when(reports.report(VIDEO_ID, "쭈유", OWNER_ID)).thenReturn(true).thenReturn(false);
        var controller = controller();

        assertEquals("reported", controller.report(OWNER_ID, request("쭈유")).get("outcome"));
        assertEquals("already", controller.report(OWNER_ID, request("쭈유")).get("outcome"));
    }

    @Test
    @DisplayName("없는(또는 REMOVED) 영상 신고는 404 — FK 오류가 아니라 계약된 상태 코드로")
    void missingVideoIs404() {
        when(videos.existsActive(VIDEO_ID)).thenReturn(false);

        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> controller().report(OWNER_ID, request("쭈유"))).getStatusCode());
    }

    @Test
    @DisplayName("영상·재료가 비면 400")
    void blankFieldsAre400() {
        var controller = controller();

        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> controller.report(OWNER_ID, request(" "))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> controller.report(OWNER_ID, new IngredientReportController.ReportRequest(null, "쭈유")))
                .getStatusCode());
    }
}
