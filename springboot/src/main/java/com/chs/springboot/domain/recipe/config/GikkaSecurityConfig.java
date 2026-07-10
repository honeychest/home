// [AGENT] /api/recipe/** 전용 보안 체인 — 분리 규율: 기존 global SecurityConfig 를 건드리지 않는다
// 인가(로그인 여부)는 필터가 아니라 CurrentUser 어댑터(@GikkaUserId 리졸버 경유)가 요청마다 판정한다 (401/403).
// 이 체인의 역할: recipe 경로를 기존 체인(전역 JWT 필터 포함)에서 떼어내 독립시키는 것.
package com.chs.springboot.domain.recipe.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class GikkaSecurityConfig {

    /** @Order(0): 기존 체인(순서 미지정 = 최후순위)보다 먼저 /api/recipe/** 를 가로챈다 */
    @Bean
    @Order(0)
    public SecurityFilterChain gikkaFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/recipe/**")
                // REST + SameSite=Lax 쿠키 조합이라 CSRF 토큰 불필요 (기존 체인과 동일 판단)
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
