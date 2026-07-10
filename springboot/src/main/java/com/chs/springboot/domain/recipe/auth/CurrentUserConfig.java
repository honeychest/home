// [AGENT] CurrentUser 어댑터 선택 — dev-user-email 설정 시 DevCurrentUser, 아니면 JwtCurrentUser.
// prod 안전장치는 DevCurrentUser 생성자가 직접 수행한다 (여기는 선택만).
package com.chs.springboot.domain.recipe.auth;

import com.chs.springboot.domain.recipe.user.GikkaUserRepository;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class CurrentUserConfig {

    @Bean
    public CurrentUser currentUser(GikkaAuthProperties properties, GikkaUserRepository users,
                                   GikkaJwt jwt, HttpServletRequest request, Environment environment) {
        String devEmail = properties.getDevUserEmail();
        if (devEmail != null && !devEmail.isBlank()) {
            return new DevCurrentUser(users, devEmail, environment);
        }
        return new JwtCurrentUser(jwt, request);
    }
}
