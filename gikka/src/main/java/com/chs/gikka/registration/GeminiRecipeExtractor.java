// [AGENT] Gemini 구현체 — 유튜브 URL 직접 전달 (영상 다운로드 없음, CONTEXT.md "추출" 확정)
// 분류(RECIPE/TIP/ETC)와 추출을 1회 호출에 합침 (2026-07-12 확정 — 한도 절약).
// media_resolution 저해상도로 시작 — 품질 부족 시에만 올린다 (2026-07-11 조사).
// 외부 HTTP 는 RestClient.Builder 주입 (PLAYBOOK 관례 4).
package com.chs.gikka.registration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.chs.gikka.external.GeminiJsonClient;
import com.chs.gikka.external.GikkaLlmProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

@Component
public class GeminiRecipeExtractor implements RecipeExtractor {

    private static final String PROMPT = """
            이 영상을 보고 판단해 주세요.
            1. category: 요리 레시피 영상이면 RECIPE, 생활팁·요령 영상이면 TIP, 둘 다 아니면 ETC.
            2. RECIPE 인 경우에만:
               - name: 요리 이름 (짧게)
               - ingredients: 재료 목록. 뒤에 유튜브 설명란 텍스트가 함께 주어지면 그 원문에 적힌
                 재료 표기를 최우선으로 사용하세요 (창작자가 직접 적은 텍스트가 가장 정확합니다).
                 설명란에 없는 재료만 화면·음성에서 보완하세요. 영상에 나온 이름 그대로 쓰고,
                 임의로 바꾸지 마세요. 양념(소금, 간장 등)도 포함. 수량·단위는 빼고 이름만.
                 아래 셋은 반드시 지키세요 (2026-07-16 실측에서 전부 어긴 사례가 나왔습니다):
                 (1) 설명란에 "재료 : A, B, C" 같은 목록이 있으면 그 항목을 하나도 빠뜨리지 말고
                     전부 넣으세요. 설명란에 재료가 적혀 있는데 목록이 비어 있으면 틀린 답입니다.
                 (2) steps 에 언급한 재료는 반드시 ingredients 에도 넣으세요. 조리 순서에는
                     나오는데 재료 목록에 없으면 그 자체로 틀린 답입니다.
                 (3) 요리 이름이 가리키는 주재료를 빠뜨리지 마세요 (예: 떡볶이의 떡,
                     단호박 튀김의 단호박). 단, 그 재료를 실제로 안 쓰는 영상이면(예: 양념장만
                     만드는 영상) 넣지 마세요 — 영상에 실제로 쓰인 것만 적는 원칙이 우선입니다.
                 함께 주어지는 영상 제목·설명란 원문에 상품명·요리명이 적혀 있으면(예: 라면
                 제품명) 음성에서 들리는 이름보다 그 표기를 우선하세요 — 음성은 비슷한 발음의
                 다른 상품명으로 잘못 들릴 수 있습니다.
               - cookMinutes: 예상 조리 시간(분). 영상에서 알 수 없으면 생략.
               - steps: 조리 순서 요약. 각 단계를 짧은 한 문장으로, 3~7개.
               - confidentSeasonings: 위 ingredients 중 소금·간장·설탕·고춧가루·참기름처럼 명백히
                 양념·조미료라고 확신하는 것만 이름 그대로 골라 담으세요. 주재료일 수도 있어 애매하면
                 넣지 마세요(넣은 것은 자동으로 양념 처리되니 확실한 것만).
            3. RECIPE 가 아닌 경우에만:
               - summary: 영상의 요점 요약 2~3문장. 나중에 다시 찾을 때 내용을 떠올릴 수 있게.
                 화면·음성·설명란에서 명확히 확인되지 않는 고유명사(인물 이름, 지명, 특정
                 사건·경기 등)는 절대 단정해서 쓰지 마세요. 확실하지 않으면 "한 선수가",
                 "경기 중" 처럼 일반적인 표현으로 대체하세요. 이 요약은 나중에 원본 영상을
                 찾기 위한 실마리일 뿐이니, 그럴듯하게 지어내는 것보다 짧고 정확한 편이
                 낫습니다 — 아는 것만 쓰고 모르는 건 생략하세요.
            4. tags: 모든 영상 공통. 이 영상을 검색할 때 쓸 만한 키워드 3~8개
               (예: 신발 끈 묶는 영상이면 ["신발끈", "매듭", "운동화"]). 짧은 명사 위주로.
               이 태그들이 나중에 이 영상을 다시 찾는 핵심 단서이니 summary 보다 중요합니다.
               태그의 철자는 name·ingredients·summary 에 쓴 표기와 정확히 일치시키세요 —
               같은 단어를 다르게 적으면(예: 요약은 "밥간장", 태그는 "밥간정") 검색이 깨집니다.
            모든 텍스트는 한국어로.
            """;

    private static final Logger log = LoggerFactory.getLogger(GeminiRecipeExtractor.class);

    private final GeminiJsonClient gemini;
    private final GikkaLlmProperties properties;
    private final GikkaTelegramNotifier notifier;

    // 모델 폐쇄 페일오버 (2026-07-12 승인): 설정 모델이 404 를 내면 폴백 모델로 전환하고
    // 재기동 전까지 유지한다 (매 호출 404 낭비 방지. 인스턴스별 판정 — 2인스턴스 각자 전환).
    private volatile boolean failedOver = false;

    public GeminiRecipeExtractor(GeminiJsonClient gemini, GikkaLlmProperties properties,
                                 GikkaTelegramNotifier notifier) {
        this.gemini = gemini;
        this.properties = properties;
        this.notifier = notifier;
    }

    /** 신고 재점검 파트 (2026-07-18) — 의심 항목을 지목해 교차 검증을 유도하되, "고치라니까
        그럴듯한 걸 지어내는" 할루시네이션을 막기 위해 "확실하지 않으면 기존 유지"를 명시한다. */
    static String reportHintText(String ingredientName) {
        return "재점검 요청: 사용자가 이전 분석의 재료 \"" + ingredientName + "\" 이(가) 이상하다고 "
                + "신고했습니다. 제목·설명란·화면·음성을 서로 교차 검증해 이 재료를 중점적으로 다시 "
                + "확인하세요 (음성은 비슷한 발음의 다른 상품명으로 잘못 인식됐을 수 있습니다). "
                + "정말 틀렸다는 확신이 들 때만 바로잡고, 확실하지 않으면 기존 표기를 그대로 유지하세요.";
    }

    @Override
    public ExtractionResult extract(String videoUrl, String title, String description) {
        return extract(videoUrl, title, description, null);
    }

    @Override
    public ExtractionResult extract(String videoUrl, String title, String description,
                                    String reportedIngredient) {
        String model = failedOver ? properties.getFallbackModel() : properties.getModel();
        try {
            return callGemini(model, videoUrl, title, description, reportedIngredient);
        } catch (HttpClientErrorException.NotFound e) {
            if (failedOver) {
                throw e; // 폴백 모델까지 404 — 일반 실패 경로(3회 후 FAILED)로
            }
            failedOver = true;
            String fallback = properties.getFallbackModel();
            log.warn("[gikka] Gemini 모델 {} 404 — {} 로 페일오버", model, fallback);
            notifier.notify("[기까] Gemini 모델 '" + model + "' 이 404(폐쇄 추정)입니다. '"
                    + fallback + "' 로 자동 전환해 분석을 계속합니다. 설정 모델 교체가 필요합니다.");
            return callGemini(fallback, videoUrl, title, description, reportedIngredient);
        }
    }

    private ExtractionResult callGemini(String model, String videoUrl, String title, String description,
                                        String reportedIngredient) {
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("fileData", Map.of("fileUri", videoUrl)));
        parts.add(Map.of("text", PROMPT));
        // 제목 원문 — 상품명·요리명이 정확히 적힌 경우가 많아 STT 오인식 교정 소스 (2026-07-18)
        if (title != null && !title.isBlank()) {
            parts.add(Map.of("text", "영상 제목 원문:\n" + title));
        }
        // 설명란(본문) 원문 — 재료가 여기 적힌 경우가 많아 최우선 활용 (2026-07-13 확정)
        if (description != null && !description.isBlank()) {
            parts.add(Map.of("text", "영상 설명란 원문:\n" + description));
        }
        // 신고 재점검 힌트 (2026-07-18) — 신고된 재료를 지목해 집중 교차 검증
        if (reportedIngredient != null && !reportedIngredient.isBlank()) {
            parts.add(Map.of("text", reportHintText(reportedIngredient)));
        }
        Map<String, Object> generationConfig = Map.of(
                "responseMimeType", "application/json",
                "responseSchema", responseSchema(),
                "mediaResolution", "MEDIA_RESOLUTION_LOW");
        // 429/503/타임아웃은 GeminiJsonClient 이 TransientFailureException 으로 매핑한다.
        // 404(모델 폐쇄)는 그대로 전파돼 아래 extract() 의 페일오버가 잡는다.
        return ExtractionResultJson.parse(gemini.generate(model, parts, generationConfig));
    }

    /** 구조화 출력 스키마 — 모델이 자유 서술로 새는 것을 방지 */
    private static Map<String, Object> responseSchema() {
        return Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "category", Map.of("type", "STRING", "enum", List.of("RECIPE", "TIP", "ETC")),
                        "name", Map.of("type", "STRING"),
                        "ingredients", Map.of("type", "ARRAY", "items", Map.of("type", "STRING")),
                        "cookMinutes", Map.of("type", "INTEGER"),
                        "steps", Map.of("type", "ARRAY", "items", Map.of("type", "STRING")),
                        "confidentSeasonings", Map.of("type", "ARRAY", "items", Map.of("type", "STRING")),
                        "summary", Map.of("type", "STRING"),
                        "tags", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))),
                "required", List.of("category"));
    }
}
