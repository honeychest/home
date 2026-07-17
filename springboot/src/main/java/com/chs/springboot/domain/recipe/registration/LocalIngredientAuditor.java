// [AGENT] 재료 사전 감사 — 로컬 모델 페일오버 (2026-07-18 확정). Gemini 가 429/503/타임아웃이면
// IngredientAuditController 가 여기로 넘긴다. gikka-local(mac-mini) 의 POST /audit 는
// yt-dlp·whisper 없이 LM Studio 텍스트 호출만 한다 — LocalRecipeExtractor 와 같은 host,
// 같은 RestClient.Builder 시임 패턴(PLAYBOOK 관례 4)이나 엔드포인트·페이로드가 달라 별도 클래스.
// 응답 JSON 은 IngredientAuditor 가 Gemini 응답을 파싱할 때 쓰는 배열과 같은 모양이라
// IngredientAuditor.parse 를 그대로 재사용한다(대표 자격·실재 검증은 여전히 컨트롤러 몫).
package com.chs.springboot.domain.recipe.registration;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class LocalIngredientAuditor {

    private final RestClient rest;
    private final GikkaMediaProperties properties;

    public LocalIngredientAuditor(RestClient.Builder builder, GikkaMediaProperties properties) {
        this.rest = builder.baseUrl(properties.getLocalExtractorBaseUrl()).build();
        this.properties = properties;
    }

    public List<IngredientAuditor.Proposal> audit(List<String> pendingNames, List<String> allRepresentatives) {
        if (!properties.isLocalExtractorEnabled()) {
            throw new LocalRecipeExtractor.LocalUnavailableException("로컬 추출기 비활성화 설정", null);
        }
        try {
            JsonNode response = rest.post()
                    .uri("/audit")
                    .body(Map.of("pendingNames", pendingNames, "allRepresentatives", allRepresentatives))
                    .retrieve()
                    .body(JsonNode.class);
            return IngredientAuditor.parse(response);
        } catch (Exception e) {
            throw new LocalRecipeExtractor.LocalUnavailableException("로컬 감사 호출 실패: " + e.getMessage(), e);
        }
    }
}
