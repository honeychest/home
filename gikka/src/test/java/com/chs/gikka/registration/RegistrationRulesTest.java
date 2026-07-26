// [AGENT] 등록 판정 고정 — 길이 컷(7분)과 재생목록 메타 색인 (DB·HTTP 없는 순수 테스트)
package com.chs.gikka.registration;

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

    @Test
    @DisplayName("변형 대표 후보: 꼬리 수량·단위를 뗀다 (계란 2개 → 계란)")
    void stripsTrailingQuantityAndUnit() {
        assertEquals("계란", RegistrationRules.representativeCandidate("계란 2개"));
        assertEquals("계란", RegistrationRules.representativeCandidate("계란2개"));
        assertEquals("두부", RegistrationRules.representativeCandidate("두부 1/2모"));
        assertEquals("청양고추", RegistrationRules.representativeCandidate("청양고추 2~3개"));
        assertEquals("돼지고기", RegistrationRules.representativeCandidate("돼지고기 300g"));
        assertEquals("간장", RegistrationRules.representativeCandidate("간장 1.5큰술"));
    }

    @Test
    @DisplayName("변형 대표 후보: 괄호 보충 설명을 뗀다 (고춧가루(고운 것) → 고춧가루)")
    void stripsParentheticalNote() {
        assertEquals("고춧가루", RegistrationRules.representativeCandidate("고춧가루(고운 것)"));
        assertEquals("계란", RegistrationRules.representativeCandidate("계란 (선택) 2개"));
    }

    @Test
    @DisplayName("뗄 게 없으면(원형) null — 병합 후보 아님")
    void unchangedNameIsNotCandidate() {
        assertNull(RegistrationRules.representativeCandidate("계란"));
        assertNull(RegistrationRules.representativeCandidate("고운 고춧가루"));
        assertNull(RegistrationRules.representativeCandidate(null));
    }

    @Test
    @DisplayName("떼고 나면 아무것도 안 남는 이름(수량으로 시작하는 상품명 등)은 null — 통째 삭제 방지")
    void nameThatStripsToNothingIsNotCandidate() {
        assertNull(RegistrationRules.representativeCandidate("3분카레"));
        assertNull(RegistrationRules.representativeCandidate("2개"));
    }

    @Test
    @DisplayName("오타·동의어 수준 차이는 기계 규칙이 건드리지 않는다 (AI 점검+오너 확정 경로 소관)")
    void typosAndSynonymsAreLeftAlone() {
        assertNull(RegistrationRules.representativeCandidate("고웃 고춧가루"));
        assertNull(RegistrationRules.representativeCandidate("고운고추가루"));
    }
}
