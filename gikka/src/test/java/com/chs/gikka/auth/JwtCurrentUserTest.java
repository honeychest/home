// [AGENT] Authorization: Bearer 헤더 파싱 규율 고정 (2026-07-2x 쿠키 -> 헤더 전환)
package com.chs.gikka.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtCurrentUserTest {

    private final GikkaJwt jwt = mock(GikkaJwt.class);

    @Test
    @DisplayName("Authorization: Bearer <토큰> 이 유효하면 그 userId 반환")
    void validBearerTokenResolvesUserId() {
        when(jwt.parseUserId("good-token")).thenReturn(42L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer good-token");

        assertEquals(42L, new JwtCurrentUser(jwt, request).currentUserId());
    }

    @Test
    @DisplayName("Authorization 헤더가 없으면 401")
    void missingHeaderIsUnauthorized() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThrows(ResponseStatusException.class, () -> new JwtCurrentUser(jwt, request).currentUserId());
    }

    @Test
    @DisplayName("Bearer 접두사가 없으면 401")
    void headerWithoutBearerPrefixIsUnauthorized() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "good-token");

        assertThrows(ResponseStatusException.class, () -> new JwtCurrentUser(jwt, request).currentUserId());
    }

    @Test
    @DisplayName("토큰이 위조·만료(parseUserId=null)면 401")
    void invalidTokenIsUnauthorized() {
        when(jwt.parseUserId("bad-token")).thenReturn(null);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer bad-token");

        assertThrows(ResponseStatusException.class, () -> new JwtCurrentUser(jwt, request).currentUserId());
    }
}
