// [AGENT] 분석 워커 — DB 대기열 + 단일 워커 (별도 큐 인프라 없음, CONTEXT.md 확정)
// 앱 2인스턴스 안전장치 둘: claimNext 의 SKIP LOCKED(항목 중복 처리 방지)
// + gemini_rate 슬롯(합산 호출 간격 하한 — 무료 분당 한도 보호).
// 429 는 전 인스턴스 공통 백오프 후 자동 재개 (재시도 횟수 소모 없음 — 확정 결정).
package com.chs.springboot.domain.recipe.registration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RegistrationWorker {

    private static final Logger log = LoggerFactory.getLogger(RegistrationWorker.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final int RATE_LIMIT_BACKOFF_SECONDS = 60;

    private final RegistrationRepository repository;
    private final RecipeExtractor extractor;
    private final GikkaMediaProperties properties;

    public RegistrationWorker(RegistrationRepository repository, RecipeExtractor extractor,
                              GikkaMediaProperties properties) {
        this.repository = repository;
        this.extractor = extractor;
        this.properties = properties;
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 15000)
    public void processOne() {
        if (properties.getGeminiApiKey().isBlank()) {
            return; // 키 없는 환경(로컬 기본)에서는 워커가 쉰다
        }
        repository.requeueStale();
        if (!repository.tryAcquireGeminiSlot(properties.getGeminiMinIntervalSeconds())) {
            return; // 다른 인스턴스가 방금 호출함 — 이번 틱은 쉼
        }
        repository.claimNext().ifPresent(this::analyze);
    }

    private void analyze(RegistrationRepository.Row item) {
        try {
            RecipeExtractor.ExtractionResult result = extractor.extract(item.url());
            Map<String, Object> recipe = null;
            if ("RECIPE".equals(result.category())) {
                // 프론트 registrationTypes.ts 의 ExtractedRecipe 와 1:1 (cookMinutes 는 null 허용)
                recipe = new LinkedHashMap<>();
                recipe.put("name", result.name() == null ? "" : result.name());
                recipe.put("ingredients", result.ingredients() == null ? List.of() : result.ingredients());
                recipe.put("cookMinutes", result.cookMinutes());
                recipe.put("steps", result.steps() == null ? List.of() : result.steps());
                recipe.put("summaryVersion", RecipeExtractor.SUMMARY_VERSION);
            }
            repository.markDone(item.userId(), item.videoId(), result.category(), recipe);
            log.info("[gikka] 분석 완료 video={} category={}", item.videoId(), result.category());
        } catch (RecipeExtractor.RateLimitedException e) {
            repository.releaseAfterRateLimit(item.userId(), item.videoId());
            repository.backoffGemini(RATE_LIMIT_BACKOFF_SECONDS);
            log.info("[gikka] Gemini 한도 도달 — {}초 휴식 후 자동 재개", RATE_LIMIT_BACKOFF_SECONDS);
        } catch (Exception e) {
            repository.markFailure(item.userId(), item.videoId(), MAX_ATTEMPTS);
            log.warn("[gikka] 분석 실패 video={} attempt={}: {}", item.videoId(), item.attemptCount(), e.getMessage());
        }
    }
}
