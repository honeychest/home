// [AGENT] 기본 어댑터 — Authorization: Bearer <JWT> 헤더로 사용자 식별. 헤더 없음·형식 오류·위조·
// 만료는 전부 401. (쿠키에서 헤더로 전환 — 네이티브 앱은 API 서버와 다른 오리진이라 쿠키가
// 자동 전송되지 않지만, 헤더는 클라이언트가 직접 실어 보내 오리진과 무관하게 동작한다.)
package com.chs.springboot.domain.recipe.auth;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class JwtCurrentUser implements CurrentUser {

    static final String AUTH_HEADER = "Authorization";
    static final String BEARER_PREFIX = "Bearer ";

    private final GikkaJwt jwt;
    private final HttpServletRequest request; // 스프링이 요청별 프록시 주입

    public JwtCurrentUser(GikkaJwt jwt, HttpServletRequest request) {
        this.jwt = jwt;
        this.request = request;
    }

    @Override
    public long currentUserId() {
        String header = request.getHeader(AUTH_HEADER);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            Long userId = jwt.parseUserId(header.substring(BEARER_PREFIX.length())); // 위조·만료면 null
            if (userId != null) {
                return userId;
            }
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다");
    }
}
