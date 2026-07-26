// [AGENT] GIS 토큰 검증의 보안 판정 고정 — 실 구글 없이 MockRestServiceServer 로 검증
package com.chs.gikka.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GoogleTokenVerifierTest {

    private static final String CLIENT_ID = "test-client-id";
    private static final String TOKENINFO_URL =
            GoogleTokenVerifier.TOKENINFO_BASE + "/tokeninfo?id_token=some-token";

    private MockRestServiceServer server;
    private GoogleTokenVerifier verifier;

    @BeforeEach
    void setUp() {
        GikkaAuthProperties properties = new GikkaAuthProperties();
        properties.setGoogleClientId(CLIENT_ID);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        verifier = new GoogleTokenVerifier(properties, builder);
    }

    private void googleReturns(String json) {
        server.expect(requestTo(TOKENINFO_URL))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("정상 토큰이면 sub·email 을 돌려준다")
    void validToken() {
        googleReturns("""
                {"aud":"%s","email_verified":"true","sub":"12345","email":"user@example.com"}
                """.formatted(CLIENT_ID));

        var identity = verifier.verify("some-token");

        assertEquals("12345", identity.sub());
        assertEquals("user@example.com", identity.email());
    }

    @Test
    @DisplayName("다른 앱에 발급된 토큰(aud 불일치)은 401")
    void rejectsWrongAudience() {
        googleReturns("""
                {"aud":"other-app","email_verified":"true","sub":"12345","email":"user@example.com"}
                """);

        var e = assertThrows(ResponseStatusException.class, () -> verifier.verify("some-token"));
        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatusCode());
    }

    @Test
    @DisplayName("이메일 미확인 계정은 401")
    void rejectsUnverifiedEmail() {
        googleReturns("""
                {"aud":"%s","email_verified":"false","sub":"12345","email":"user@example.com"}
                """.formatted(CLIENT_ID));

        var e = assertThrows(ResponseStatusException.class, () -> verifier.verify("some-token"));
        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatusCode());
    }

    @Test
    @DisplayName("구글이 거부(400 = 위조·만료)하면 401")
    void rejectsWhenGoogleRejects() {
        server.expect(requestTo(TOKENINFO_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        var e = assertThrows(ResponseStatusException.class, () -> verifier.verify("some-token"));
        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatusCode());
    }
}
