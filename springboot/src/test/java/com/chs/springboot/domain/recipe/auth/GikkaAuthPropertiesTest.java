// [AGENT] 오너 전용 화면 접근 판정 고정 (2026-07-13 확정 — 홈 탭 모니터링 링크 노출 조건)
package com.chs.springboot.domain.recipe.auth;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GikkaAuthPropertiesTest {

    @Test
    @DisplayName("허용 목록에 있는 이메일만 오너로 판정")
    void ownerRequiresAllowedEmail() {
        GikkaAuthProperties properties = new GikkaAuthProperties();
        properties.setAllowedEmails(List.of("owner@example.com"));

        assertTrue(properties.isOwner("owner@example.com"));
        assertFalse(properties.isOwner("other@example.com"));
    }

    @Test
    @DisplayName("허용 목록이 비면(2단계 공개 상태) 아무도 오너가 아님 — 안전 기본값")
    void noOwnerWhenAllowlistEmpty() {
        GikkaAuthProperties properties = new GikkaAuthProperties();

        assertFalse(properties.isOwner("owner@example.com"));
    }
}
