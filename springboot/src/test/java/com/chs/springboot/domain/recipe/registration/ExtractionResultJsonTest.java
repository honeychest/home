// [AGENT] 추출 결과 파싱 규칙 고정 — Gemini·로컬이 공유하는 유일한 파서 (HTTP 없는 순수 테스트).
// 두 구현체에 복제돼 있던 파서를 한 곳으로 합치면서 테스트도 여기로 모음 (2026-07-15 점검).
package com.chs.springboot.domain.recipe.registration;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ExtractionResultJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    @Test
    @DisplayName("RECIPE: 이름·재료·단계를 파싱하고, 없는 필드(cookMinutes)는 null")
    void parsesRecipe() throws Exception {
        var result = ExtractionResultJson.parse(json("""
                {"category":"RECIPE","name":"김치찌개","ingredients":["김치"],"steps":["끓인다"]}
                """));

        assertEquals("RECIPE", result.category());
        assertEquals("김치찌개", result.name());
        assertEquals(List.of("김치"), result.ingredients());
        assertEquals(List.of("끓인다"), result.steps());
        assertNull(result.cookMinutes());
        assertNull(result.summary()); // RECIPE 요약은 name·steps 가 대신함
    }

    @Test
    @DisplayName("RECIPE 가 아니면 레시피 필드는 전부 null, summary 만 채워짐")
    void parsesNonRecipe() throws Exception {
        var result = ExtractionResultJson.parse(json("""
                {"category":"TIP","summary":"신발끈 묶는 법","tags":["신발끈","매듭"]}
                """));

        assertEquals("TIP", result.category());
        assertNull(result.name());
        assertNull(result.ingredients());
        assertNull(result.cookMinutes());
        assertEquals("신발끈 묶는 법", result.summary());
        assertEquals(List.of("신발끈", "매듭"), result.tags());
    }

    @Test
    @DisplayName("category 가 없으면 ETC 로 본다 — 분류 불명은 기타 (안전 기본값)")
    void missingCategoryBecomesEtc() throws Exception {
        assertEquals("ETC", ExtractionResultJson.parse(json("{}")).category());
    }

    @Test
    @DisplayName("태그는 전 분류 공통으로 파싱된다 (검색의 밑거름 — 2026-07-13 확정)")
    void tagsAreParsedForRecipeToo() throws Exception {
        var result = ExtractionResultJson.parse(json("""
                {"category":"RECIPE","name":"김치찌개","tags":["김치","찌개"]}
                """));

        assertEquals(List.of("김치", "찌개"), result.tags());
    }

    @Test
    @DisplayName("빈 문자열·공백뿐인 항목은 목록에서 빠지고, 양옆 공백은 잘린다")
    void blankListEntriesAreDropped() throws Exception {
        var result = ExtractionResultJson.parse(json("""
                {"category":"RECIPE","ingredients":["  무  ","","   ","참치"]}
                """));

        assertEquals(List.of("무", "참치"), result.ingredients());
    }

    @Test
    @DisplayName("transcriptChars: 로컬 응답엔 실려 오고 (pattern-raw-signal)")
    void keepsTranscriptCharsWhenLocalSendsIt() throws Exception {
        var result = ExtractionResultJson.parse(json("""
                {"category":"RECIPE","name":"참치무조림","transcriptChars":320}
                """));

        assertEquals(320, result.transcriptChars());
    }

    @Test
    @DisplayName("transcriptChars: Gemini 응답엔 그 필드가 없어 null — 그 null 이 '로컬이 안 돌았다'는 사실")
    void transcriptCharsIsNullWhenAbsent() throws Exception {
        var result = ExtractionResultJson.parse(json("""
                {"category":"TIP","summary":"요약"}
                """));

        assertNull(result.transcriptChars());
    }

    @Test
    @DisplayName("confidentSeasonings: RECIPE 에서 모델이 확신한 양념 목록을 파싱한다 (5차-4 슬라이스1-C)")
    void parsesConfidentSeasonings() throws Exception {
        var result = ExtractionResultJson.parse(json("""
                {"category":"RECIPE","name":"김치찌개","ingredients":["김치","간장"],"confidentSeasonings":["간장"]}
                """));

        assertEquals(List.of("간장"), result.confidentSeasonings());
    }

    @Test
    @DisplayName("confidentSeasonings: 없거나 RECIPE 가 아니면 빈 목록 (안전 기본값)")
    void confidentSeasoningsEmptyWhenAbsentOrNonRecipe() throws Exception {
        assertEquals(List.of(), ExtractionResultJson.parse(json("""
                {"category":"RECIPE","name":"물김치"}
                """)).confidentSeasonings());
        assertEquals(List.of(), ExtractionResultJson.parse(json("""
                {"category":"TIP","summary":"요약"}
                """)).confidentSeasonings());
    }
}
