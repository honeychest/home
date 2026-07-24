// [AGENT] /api/recipe/** 전용 보안 체인 — 분리 규율: 기존 global SecurityConfig 를 건드리지 않는다
// 인가(로그인 여부)는 필터가 아니라 CurrentUser 어댑터(@GikkaUserId 리졸버 경유)가 요청마다 판정한다 (401/403).
// 이 체인의 역할: recipe 경로를 기존 체인(전역 JWT 필터 포함)에서 떼어내 독립시키는 것.
package com.chs.springboot.domain.recipe.config;

import java.util.List;

import com.chs.springboot.domain.recipe.auth.GikkaAuthProperties;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class GikkaSecurityConfig {

    /** @Order(0): 기존 체인(순서 미지정 = 최후순위)보다 먼저 /api/recipe/** 를 가로챈다 */
    @Bean
    @Order(0)
    public SecurityFilterChain gikkaFilterChain(HttpSecurity http, GikkaAuthProperties properties) throws Exception {
        http
                .securityMatcher("/api/recipe/**")
                // Authorization 헤더(Bearer 토큰) 인증이라 CSRF 위험 자체가 없음 — 쿠키처럼
                // 브라우저가 자동으로 실어 보내지 않아 위조 요청이 헤더를 못 붙인다.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource(properties)))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /** allowedOrigins 가 비어 있으면(지금의 같은 오리진 PWA) CORS 등록 자체를 하지 않는다 —
        등록해두면 Origin 헤더가 붙은 같은 오리진 요청도 매칭되는 오리진이 없어 403 이 났다
        (전역 SecurityConfig.corsConfigurationSource() 의 !allowedOriginsRaw.isBlank() 가드와
        같은 패턴, recipe 격리 규율상 공용 유틸로 뽑지 않고 각자 소유). 네이티브 오리진이
        정해지면 gikka.auth.allowed-origins 설정만 채우면 된다. */
    private CorsConfigurationSource corsConfigurationSource(GikkaAuthProperties properties) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        List<String> allowedOrigins = properties.getAllowedOrigins();
        if (!allowedOrigins.isEmpty()) {
            CorsConfiguration configuration = new CorsConfiguration();
            configuration.setAllowedOrigins(allowedOrigins);
            configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
            configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
            source.registerCorsConfiguration("/api/recipe/**", configuration);
        }
        return source;
    }
}
