// [AGENT] 오너 전용 화면 접근 판정 고정 (2026-07-20 갱신 — ownerEmail 전용 설정으로 분리,
// 홈 탭 모니터링 링크 노출 조건)
package com.chs.springboot.domain.recipe.auth;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GikkaAuthPropertiesTest {

    @Test
    @DisplayName("ownerEmail 과 같은 이메일만 오너로 판정")
    void ownerRequiresOwnerEmail() {
        GikkaAuthProperties properties = new GikkaAuthProperties();
        properties.setOwnerEmail("owner@example.com");

        assertTrue(properties.isOwner("owner@example.com"));
        assertFalse(properties.isOwner("other@example.com"));
    }

    @Test
    @DisplayName("ownerEmail 이 비어 있으면 아무도 오너가 아님 — 안전 기본값")
    void noOwnerWhenOwnerEmailUnset() {
        GikkaAuthProperties properties = new GikkaAuthProperties();

        assertFalse(properties.isOwner("owner@example.com"));
    }

    @Test
    @DisplayName("로그인 허용 목록(allowedEmails)이 비어 공개 상태여도 오너 판정은 그대로 동작 "
            + "(2026-07-20 확정 — 예전엔 이 목록을 비우면 오너도 같이 막혔음)")
    void ownerStillWorksWhenLoginIsPublic() {
        GikkaAuthProperties properties = new GikkaAuthProperties();
        properties.setOwnerEmail("owner@example.com");
        properties.setAllowedEmails(List.of());

        assertTrue(properties.isOwner("owner@example.com"));
    }
}
