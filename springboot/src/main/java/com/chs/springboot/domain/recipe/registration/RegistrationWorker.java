// [AGENT] 분석 워커 — DB 대기열 + 단일 워커 (별도 큐 인프라 없음, CONTEXT.md 확정)
// 앱 2인스턴스 안전장치 둘: claimNext 의 SKIP LOCKED(항목 중복 처리 방지)
// + gemini_rate 슬롯(합산 호출 간격 하한 — 무료 분당 한도 보호).
// 429·503(과부하)·타임아웃은 전 인스턴스 공통 백오프 후 자동 재개 (재시도 횟수 소모 없음 — 확정 결정,
// 2026-07-13 실측으로 503·타임아웃까지 확장: 영상 문제가 아니라 Gemini 쪽 일시적 사정이라 동일 취급).
// 2026-07-13 재편: 대기열은 video 테이블 기준(사용자 무관 — 같은 영상 중복 분석 방지가 목적).
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
    private static final int TRANSIENT_FAILURE_BACKOFF_SECONDS = 60;

    private final VideoRepository videos;
    private final RecipeExtractor extractor;
    private final GikkaMediaProperties properties;

    public RegistrationWorker(VideoRepository videos, RecipeExtractor extractor,
                              GikkaMediaProperties properties) {
        this.videos = videos;
        this.extractor = extractor;
        this.properties = properties;
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 15000)
    public void processOne() {
        if (properties.getGeminiApiKey().isBlank()) {
            return; // 키 없는 환경(로컬 기본)에서는 워커가 쉰다
        }
        videos.touchHeartbeat(); // 일할 게 없어도 갱신 — 모니터링 화면의 워커 생존 신호 (2026-07-13 확정)
        videos.requeueStale();
        if (!videos.tryAcquireGeminiSlot(properties.getGeminiMinIntervalSeconds())) {
            return; // 다른 인스턴스가 방금 호출함 — 이번 틱은 쉼
        }
        videos.claimNext().ifPresent(this::analyze);
    }

    private void analyze(VideoRepository.Row item) {
        try {
            RecipeExtractor.ExtractionResult result = extractor.extract(item.url(), item.description());
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
            // 요약(TIP/ETC)·검색 태그(전 분류)는 같은 호출에서 나옴 — 컬럼에 함께 저장 (2026-07-13 확정)
            List<String> signals = RegistrationRules.analysisSignals(item.description(), result.transcriptChars());
            videos.markDone(item.videoId(), result.category(), recipe,
                    result.summary(), result.tags(), RecipeExtractor.SUMMARY_VERSION, signals);
            log.info("[gikka] 분석 완료 video={} category={}", item.videoId(), result.category());
        } catch (RecipeExtractor.TransientFailureException e) {
            videos.releaseAfterRateLimit(item.videoId(), e.getMessage());
            videos.backoffGemini(TRANSIENT_FAILURE_BACKOFF_SECONDS);
            log.info("[gikka] Gemini 일시적 실패(한도·과부하·타임아웃 등) — {}초 휴식 후 자동 재개: {}",
                    TRANSIENT_FAILURE_BACKOFF_SECONDS, e.getMessage());
        } catch (Exception e) {
            videos.markFailure(item.videoId(), MAX_ATTEMPTS, e.getMessage());
            log.warn("[gikka] 분석 실패 video={} attempt={}: {}", item.videoId(), item.attemptCount(), e.getMessage());
        }
    }
}
