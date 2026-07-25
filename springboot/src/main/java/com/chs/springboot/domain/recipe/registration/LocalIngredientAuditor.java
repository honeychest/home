// [AGENT] 재료 사전 판정의 로컬 어댑터 (2026-07-18 확정). gikka-local(mac-mini) 의 POST /audit 는
// yt-dlp·whisper 없이 LM Studio 텍스트 호출만 한다 — LocalRecipeExtractor 와 같은 host,
// 같은 RestClient.Builder 시임 패턴(PLAYBOOK 관례 4)이나 엔드포인트·페이로드가 달라 별도 클래스.
// 응답 JSON 은 Gemini 채널과 같은 배열 모양이라 파싱은 시임(IngredientJudge.parse)이 소유한다 —
// 2026-07-25 이전엔 이 클래스가 IngredientAuditor.parse 를 들여다봤다(어댑터가 남의 어댑터에 의존).
// 어느 순서로 두 채널을 부를지(라우팅)와 제안 검증은 DictionaryJudge 몫.
package com.chs.springboot.domain.recipe.registration;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class LocalIngredientAuditor implements IngredientJudge {

    private final RestClient rest;
    private final GikkaMediaProperties properties;

    public LocalIngredientAuditor(RestClient.Builder builder, GikkaMediaProperties properties) {
        this.rest = builder.baseUrl(properties.getLocalExtractorBaseUrl()).build();
        this.properties = properties;
    }

    @Override
    public List<Proposal> audit(List<String> pendingNames, List<String> allRepresentatives) {
        if (!properties.isLocalExtractorEnabled()) {
            throw new LocalRecipeExtractor.LocalUnavailableException("로컬 추출기 비활성화 설정", null);
        }
        try {
            JsonNode response = rest.post()
                    .uri("/audit")
                    .body(Map.of("pendingNames", pendingNames, "allRepresentatives", allRepresentatives))
                    .retrieve()
                    .body(JsonNode.class);
            return IngredientJudge.parse(response);
        } catch (Exception e) {
            throw new LocalRecipeExtractor.LocalUnavailableException("로컬 감사 호출 실패: " + e.getMessage(), e);
        }
    }
}
