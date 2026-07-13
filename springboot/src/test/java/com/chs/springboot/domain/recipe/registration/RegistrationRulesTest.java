// [AGENT] 등록 판정 고정 — 길이 컷(7분)과 재생목록 메타 색인 (DB·HTTP 없는 순수 테스트)
package com.chs.springboot.domain.recipe.registration;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RegistrationRulesTest {

    @Test
    @DisplayName("길이 컷: 7분 초과만 TOO_LONG — 정확히 7분(420초)은 분석 대상")
    void cutsOnlyOverMax() {
        assertEquals("WAITING", RegistrationRules.initialStatus(420, 7));
        assertEquals("TOO_LONG", RegistrationRules.initialStatus(421, 7));
        assertEquals("WAITING", RegistrationRules.initialStatus(59, 7));
    }

    @Test
    @DisplayName("길이를 모르면(메타 실패, null) 컷하지 않는다 — 막는 것보다 분석 시도가 낫다 (확정 결정)")
    void unknownDurationIsNotCut() {
        assertEquals("WAITING", RegistrationRules.initialStatus(null, 7));
    }

    @Test
    @DisplayName("컷 기준은 상수가 아니라 설정(maxVideoMinutes)을 따른다")
    void maxIsConfigurable() {
        assertEquals("TOO_LONG", RegistrationRules.initialStatus(181, 3));
        assertEquals("WAITING", RegistrationRules.initialStatus(181, 7));
    }

    @Test
    @DisplayName("재생목록 메타 색인: ID 로 찾고, 없는 영상은 null (등록은 막지 않음)")
    void indexesMetadataById() {
        var a = new VideoMetadataClient.VideoMetadata("aaaaaaaaaaa", "김치찌개", "http://t/a.jpg", 60);
        var b = new VideoMetadataClient.VideoMetadata("bbbbbbbbbbb", "두부조림", "http://t/b.jpg", 90);
        Map<String, VideoMetadataClient.VideoMetadata> byId = RegistrationRules.metadataById(List.of(a, b));

        assertEquals(a, byId.get("aaaaaaaaaaa"));
        assertEquals(b, byId.get("bbbbbbbbbbb"));
        assertNull(byId.get("ccccccccccc"));
    }

    @Test
    @DisplayName("중복 ID 메타는 첫 항목 우선 (API 이상 응답에도 색인이 깨지지 않음)")
    void duplicateMetadataKeepsFirst() {
        var first = new VideoMetadataClient.VideoMetadata("aaaaaaaaaaa", "먼저", null, 10);
        var dup = new VideoMetadataClient.VideoMetadata("aaaaaaaaaaa", "나중", null, 20);

        assertEquals(first, RegistrationRules.metadataById(List.of(first, dup)).get("aaaaaaaaaaa"));
    }
}
