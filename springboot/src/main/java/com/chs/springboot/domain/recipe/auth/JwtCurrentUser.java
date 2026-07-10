// [AGENT] 기본 어댑터 — gikka_token 쿠키(JWT)로 사용자 식별. 쿠키 없음·위조·만료는 전부 401.
package com.chs.springboot.domain.recipe.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class JwtCurrentUser implements CurrentUser {

    private final GikkaJwt jwt;
    private final HttpServletRequest request; // 스프링이 요청별 프록시 주입

    public JwtCurrentUser(GikkaJwt jwt, HttpServletRequest request) {
        this.jwt = jwt;
        this.request = request;
    }

    @Override
    public long currentUserId() {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (GikkaAuthController.COOKIE_NAME.equals(cookie.getName())) {
                    Long userId = jwt.parseUserId(cookie.getValue()); // 위조·만료면 null
                    if (userId != null) {
                        return userId;
                    }
                }
            }
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다");
    }
}
