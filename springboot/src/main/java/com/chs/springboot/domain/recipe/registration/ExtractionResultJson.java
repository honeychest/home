// [AGENT] 추출 결과 JSON → ExtractionResult 순수 파서 (pattern-pure-rules).
// Gemini·로컬 두 구현체가 같은 모양의 JSON 을 돌려주므로 파싱 규칙은 이 파일 하나가 소유한다
// (2026-07-15 아키텍처 점검 — 두 구현체에 사실상 같은 코드가 복제돼 있어 한쪽만 고치면
// 조용히 어긋나는 구조였음. 파싱을 바꿀 일이 생기면 여기만 고친다).
// transcriptChars 는 로컬 호스트 서비스만 실어 보내는 필드 — Gemini 응답엔 없어 자연히 null 이
// 되고, 그 null 이 곧 "로컬이 안 돌았다"는 사실이다 (RecipeExtractor 주석 참고).
package com.chs.springboot.domain.recipe.registration;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

final class ExtractionResultJson {

    private ExtractionResultJson() {
    }

    static RecipeExtractor.ExtractionResult parse(JsonNode json) {
        String category = json.path("category").asText("ETC");
        List<String> tags = toList(json.path("tags")); // 검색 태그는 전 분류 공통 (2026-07-13 확정)
        Integer transcriptChars = json.hasNonNull("transcriptChars")
                ? json.get("transcriptChars").asInt() : null;
        if (!"RECIPE".equals(category)) {
            return new RecipeExtractor.ExtractionResult(category, null, null, null, null,
                    json.path("summary").asText(null), tags, transcriptChars, List.of());
        }
        return new RecipeExtractor.ExtractionResult(
                category,
                json.path("name").asText(null),
                toList(json.path("ingredients")),
                json.hasNonNull("cookMinutes") ? json.get("cookMinutes").asInt() : null,
                toList(json.path("steps")),
                null, // RECIPE 요약은 name·steps 가 대신함
                tags, transcriptChars,
                toList(json.path("confidentSeasonings"))); // 모델이 확신한 양념 (5차-4 슬라이스1-C)
    }

    private static List<String> toList(JsonNode array) {
        List<String> list = new ArrayList<>();
        for (JsonNode node : array) {
            if (!node.asText().isBlank()) {
                list.add(node.asText().trim());
            }
        }
        return list;
    }
}
