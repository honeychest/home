// [AGENT] gikka 인증 API — GIS 로그인·세션 확인·로그아웃 (CONTEXT.md 인증 절)
package com.chs.springboot.domain.recipe.auth;

import com.chs.springboot.domain.recipe.user.GikkaUserRepository;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/recipe/auth")
public class GikkaAuthController {

    static final String COOKIE_NAME = "gikka_token";

    private final GikkaAuthProperties properties;
    private final GoogleTokenVerifier verifier;
    private final GikkaUserRepository users;
    private final GikkaJwt jwt;

    public GikkaAuthController(GikkaAuthProperties properties, GoogleTokenVerifier verifier,
                               GikkaUserRepository users, GikkaJwt jwt) {
        this.properties = properties;
        this.verifier = verifier;
        this.users = users;
        this.jwt = jwt;
    }

    /** GIS 버튼이 주는 credential(ID 토큰) */
    public record LoginRequest(String credential) {
    }

    /** canViewMonitor: 홈 탭 모니터링 링크 노출 조건 (2026-07-13 확정 — 허용 목록 재사용) */
    public record MeResponse(String email, boolean canViewMonitor) {
    }

    @PostMapping("/google")
    public MeResponse login(@RequestBody LoginRequest request, HttpServletResponse response) {
        GoogleTokenVerifier.GoogleIdentity identity = verifier.verify(request.credential());

        // 1단계 허용 목록 스위치: 목록이 비어 있으면 누구나(공개 상태), 있으면 목록만
        if (!properties.getAllowedEmails().isEmpty()
                && !properties.getAllowedEmails().contains(identity.email())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "아직 공개되지 않은 서비스입니다");
        }

        long userId = users.findOrCreate(identity.sub(), identity.email());
        response.addCookie(sessionCookie(jwt.issue(userId), (int) GikkaJwt.VALIDITY.toSeconds()));
        return new MeResponse(identity.email(), properties.isOwner(identity.email()));
    }

    /** 프론트 진입 시 세션 확인용. 미로그인 401 (dev 폴백 환경에서는 dev 사용자로 통과) */
    @GetMapping("/me")
    public MeResponse me(@GikkaUserId long userId) {
        String email = users.findEmail(userId);
        return new MeResponse(email, properties.isOwner(email));
    }

    @PostMapping("/logout")
    public void logout(HttpServletResponse response) {
        response.addCookie(sessionCookie("", 0)); // Max-Age 0 = 즉시 삭제
    }

    /** HttpOnly(JS 접근 차단) + Secure(https 전송 한정) + SameSite=Lax(타 사이트발 요청에 미전송) + /api/recipe 한정 */
    private Cookie sessionCookie(String value, int maxAgeSeconds) {
        Cookie cookie = new Cookie(COOKIE_NAME, value);
        cookie.setHttpOnly(true);
        // 로그인(발급)은 https 배포에서만 일어남 — local 은 dev 폴백이라 쿠키 자체를 안 씀 (부작용 없음)
        cookie.setSecure(true);
        cookie.setPath("/api/recipe");
        cookie.setMaxAge(maxAgeSeconds);
        cookie.setAttribute("SameSite", "Lax");
        return cookie;
    }
}
