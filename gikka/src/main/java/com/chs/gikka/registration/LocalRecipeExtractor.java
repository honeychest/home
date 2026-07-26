// [AGENT] 로컬 모델 페일오버 구현체 (2026-07-14 확정) — mac-mini 호스트에서 상시 도는
// 파이썬 서비스(chs/server/gikka-local, yt-dlp→ffmpeg→whisper turbo→LM Studio)를
// host.docker.internal 로 호출한다. 앱이 도커 컨테이너(Alpine)라 yt-dlp·ffmpeg·whisper 를
// 직접 실행할 수 없어 프로세스 실행이 아니라 네트워크 호출로 격리(LM Studio 와 동일 패턴).
// 외부 HTTP 는 RestClient.Builder 주입 (PLAYBOOK 관례 4, GeminiRecipeExtractor 와 동일 시임).
package com.chs.gikka.registration;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import com.chs.gikka.external.GikkaHostServiceProperties;
import com.chs.gikka.external.LocalUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class LocalRecipeExtractor implements RecipeExtractor {

    /** health 는 오너 모니터 폴링(2초)에 얹히므로 빨리 포기해야 한다 — 공용 빌더의 readTimeout 은
        300초(추출용으로 필요한 값)라 그대로 쓰면 mac-mini 가 멎었을 때 모니터 화면이 5분간 얼어붙는다 */
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(2);

    private final RestClient rest;
    private final RestClient healthRest;
    private final GikkaHostServiceProperties properties;

    public LocalRecipeExtractor(RestClient.Builder builder, GikkaHostServiceProperties properties) {
        String baseUrl = properties.getBaseUrl();
        this.rest = builder.baseUrl(baseUrl).build();
        this.healthRest = builder.clone().baseUrl(baseUrl)
                .requestFactory(healthRequestFactory()).build();
        this.properties = properties;
    }

    /** HTTP/1.1 고정은 공용 설정과 같은 이유(JDK HttpClient 의 평문 HTTP/2 hang) — 여기서
        requestFactory 를 갈아끼우면 그 설정도 함께 덮이므로 다시 명시한다 */
    private static JdkClientHttpRequestFactory healthRequestFactory() {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(HEALTH_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(HEALTH_TIMEOUT);
        return factory;
    }

    // 서비스 미기동 등 로컬 파이프라인 자체가 안 되는 상황은 external.LocalUnavailableException —
    // 2026-07-26 중립 지대로 옮겼다. 재료 사전의 로컬 어댑터(LocalIngredientAuditor)도 같은 뜻을
    // 던져야 하는데, 여기 중첩 클래스로 두면 사전이 "영상 추출 어댑터"를 import 하게 된다.

    /**
     * 호스트 서비스가 스스로 관측한 사실 (2026-07-16 신설, 오너 모니터링용).
     * 판정·문구는 하지 않는다 — 사실만 나르고 "정상인가"는 화면이 정한다 (pattern-raw-signal).
     * 서비스가 안 뜨거나 옛 버전이라 /health 가 없으면 null — 그 null 자체가
     * "지금 로컬을 못 쓴다"는 사실이다.
     */
    public JsonNode health() {
        if (!properties.isEnabled()) {
            return null;
        }
        try {
            return healthRest.get().uri("/health").retrieve().body(JsonNode.class);
        } catch (Exception e) {
            return null; // 모니터 폴링에 얹히므로 여기서 예외를 던지면 화면 전체가 죽는다
        }
    }

    @Override
    public ExtractionResult extract(String videoUrl, String title, String description) {
        if (!properties.isEnabled()) {
            throw new LocalUnavailableException("로컬 추출기 비활성화 설정", null);
        }
        // title·description 은 없을 수 있어 있는 것만 싣는다 (Map.of 는 null 값을 못 담는다)
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("videoUrl", videoUrl);
        if (title != null && !title.isBlank()) {
            body.put("title", title);
        }
        if (description != null) {
            body.put("description", description);
        }
        try {
            JsonNode response = rest.post()
                    .uri("/extract")
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            // 호스트 서비스는 ExtractionResult 와 1:1 JSON 을 그대로 돌려줌 (봉투 없음) —
            // transcriptChars(whisper 전사 글자 수)는 로컬만 아는 사실이라 여기서만 실려 온다
            return ExtractionResultJson.parse(response);
        } catch (Exception e) {
            // 네트워크 오류·서비스 다운·타임아웃 전부 "로컬 불가" 로 묶는다 — Hybrid 가 Gemini 로 폴백
            throw new LocalUnavailableException("로컬 추출 서비스 호출 실패: " + e.getMessage(), e);
        }
    }
}
