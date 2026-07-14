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
        var a = new VideoMetadataClient.VideoMetadata("aaaaaaaaaaa", "김치찌개", "http://t/a.jpg", 60, null);
        var b = new VideoMetadataClient.VideoMetadata("bbbbbbbbbbb", "두부조림", "http://t/b.jpg", 90, null);
        Map<String, VideoMetadataClient.VideoMetadata> byId = RegistrationRules.metadataById(List.of(a, b));

        assertEquals(a, byId.get("aaaaaaaaaaa"));
        assertEquals(b, byId.get("bbbbbbbbbbb"));
        assertNull(byId.get("ccccccccccc"));
    }

    @Test
    @DisplayName("중복 ID 메타는 첫 항목 우선 (API 이상 응답에도 색인이 깨지지 않음)")
    void duplicateMetadataKeepsFirst() {
        var first = new VideoMetadataClient.VideoMetadata("aaaaaaaaaaa", "먼저", null, 10, null);
        var dup = new VideoMetadataClient.VideoMetadata("aaaaaaaaaaa", "나중", null, 20, null);

        assertEquals(first, RegistrationRules.metadataById(List.of(first, dup)).get("aaaaaaaaaaa"));
    }

    @Test
    @DisplayName("분석 신호: FRAMES 는 항상 포함, 설명란·전사는 있을 때만 추가")
    void analysisSignalsIncludesAvailableInputsOnly() {
        assertEquals(List.of("FRAMES"), RegistrationRules.analysisSignals(null, null));
        assertEquals(List.of("FRAMES", "DESCRIPTION"), RegistrationRules.analysisSignals("설명란 원문", null));
        assertEquals(List.of("FRAMES", "TRANSCRIPT"), RegistrationRules.analysisSignals(null, 50));
        assertEquals(List.of("FRAMES", "DESCRIPTION", "TRANSCRIPT"),
                RegistrationRules.analysisSignals("설명란", 50));
    }

    @Test
    @DisplayName("전사 글자 수가 임계값 미만이면(잡음 수준) TRANSCRIPT 신호를 넣지 않는다")
    void sparseTranscriptIsNotCountedAsSignal() {
        assertEquals(List.of("FRAMES"),
                RegistrationRules.analysisSignals(null, RegistrationRules.MIN_TRANSCRIPT_CHARS - 1));
        assertEquals(List.of("FRAMES", "TRANSCRIPT"),
                RegistrationRules.analysisSignals(null, RegistrationRules.MIN_TRANSCRIPT_CHARS));
    }

    @Test
    @DisplayName("빈 설명란(공백뿐)은 DESCRIPTION 신호에 안 들어간다")
    void blankDescriptionIsNotCountedAsSignal() {
        assertEquals(List.of("FRAMES"), RegistrationRules.analysisSignals("   ", null));
    }
}
