// [AGENT] CurrentUser 어댑터 선택·prod 안전장치 고정 — 규율을 주석이 아니라 테스트가 지키게 한다
package com.chs.springboot.domain.recipe.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CurrentUserConfigTest {

    private final CurrentUserConfig config = new CurrentUserConfig();

    private static GikkaAuthProperties propertiesWithDevEmail(String devEmail) {
        GikkaAuthProperties properties = new GikkaAuthProperties();
        properties.setDevUserEmail(devEmail);
        return properties;
    }

    private static MockEnvironment environmentWithProfile(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return environment;
    }

    @Test
    @DisplayName("dev-user-email 미설정이면 JWT 어댑터 (배포 기본)")
    void jwtAdapterByDefault() {
        CurrentUser currentUser = config.currentUser(
                propertiesWithDevEmail(null), null, null, null, environmentWithProfile("prod"));

        assertInstanceOf(JwtCurrentUser.class, currentUser);
    }

    @Test
    @DisplayName("dev-user-email 설정 + local 프로파일이면 dev 어댑터")
    void devAdapterOnLocal() {
        CurrentUser currentUser = config.currentUser(
                propertiesWithDevEmail("dev@example.com"), null, null, null, environmentWithProfile("local"));

        assertInstanceOf(DevCurrentUser.class, currentUser);
    }

    @Test
    @DisplayName("dev-user-email 설정 + prod 프로파일이면 기동 실패 (무인증 통과 구멍 차단)")
    void devAdapterRefusesProd() {
        assertThrows(IllegalStateException.class, () -> config.currentUser(
                propertiesWithDevEmail("dev@example.com"), null, null, null, environmentWithProfile("prod")));
    }
}
