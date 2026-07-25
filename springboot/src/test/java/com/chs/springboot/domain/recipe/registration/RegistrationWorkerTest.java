// [AGENT] 워커 틱의 판정 고정 — DB·HTTP 없는 순수 테스트 (협력자 4개 전부 주입식이라 가능).
// 2026-07-15 신설: 그동안 워커에 테스트가 하나도 없었다. 특히 "일시적 실패는 시도 횟수를
// 소모하지 않는다"(claimNext 의 +1 을 releaseAfterRateLimit 의 -1 이 상쇄)는 이 앱의 핵심
// 불변식인데 코드 주석으로만 주장되고 있었음 — 여기서 잠근다.
package com.chs.springboot.domain.recipe.registration;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.chs.springboot.domain.recipe.dictionary.IngredientChangeLogRepository;
import com.chs.springboot.domain.recipe.dictionary.IngredientDictionaryRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RegistrationWorkerTest {

    private static final String VIDEO_ID = "aaaaaaaaaaa";
    private static final String URL = "https://www.youtube.com/watch?v=aaaaaaaaaaa";

    private final VideoRepository videos = mock(VideoRepository.class);
    private final GeminiRateLimiter rateLimiter = mock(GeminiRateLimiter.class);
    private final RecipeExtractor extractor = mock(RecipeExtractor.class);
    private final GikkaMediaProperties properties = new GikkaMediaProperties();
    private final IngredientDictionaryRepository dictionary = mock(IngredientDictionaryRepository.class);
    private final IngredientChangeLogRepository changeLog = mock(IngredientChangeLogRepository.class);
    private final IngredientAutoJudge autoJudge = mock(IngredientAutoJudge.class);
    private final IngredientReportRepository reports = mock(IngredientReportRepository.class);

    private RegistrationWorker worker() {
        return new RegistrationWorker(videos, rateLimiter, extractor, properties, dictionary,
                changeLog, autoJudge, reports);
    }

    private static VideoRepository.Row row(String description) {
        return row(description, null);
    }

    /** reportIngredient 지정판 — 신고 재점검 경로 테스트용 */
    private static VideoRepository.Row row(String description, String reportIngredient) {
        return new VideoRepository.Row(VIDEO_ID, URL, "YOUTUBE", null, "ANALYZING",
                "제목", null, 60, description, null, null, null,
                1, null, null, OffsetDateTime.now(), null, 0, reportIngredient);
    }

    /** 슬롯 획득까지 통과시켜 실제 분석 경로를 태우는 준비 */
    private void givenSlotAcquired() {
        properties.setGeminiApiKey("test-key");
        when(rateLimiter.tryAcquireSlot(anyInt())).thenReturn(true);
    }

    @Test
    @DisplayName("API 키가 없으면(로컬 기본) 워커는 아무것도 하지 않는다 — 생존 신호조차 안 남김")
    void blankApiKeyDoesNothing() {
        properties.setGeminiApiKey("");

        worker().processOne();

        verifyNoInteractions(videos, rateLimiter, extractor);
    }

    @Test
    @DisplayName("슬롯을 못 잡으면(다른 인스턴스가 방금 호출) 대기열을 건드리지 않는다 — 생존 신호는 남김")
    void withoutSlotDoesNotClaim() {
        properties.setGeminiApiKey("test-key");
        when(rateLimiter.tryAcquireSlot(anyInt())).thenReturn(false);

        worker().processOne();

        verify(rateLimiter).touchHeartbeat(); // 일할 게 없어도 살아있음을 알림 (모니터링 화면)
        verify(videos, never()).claimNext();
        verifyNoInteractions(extractor);
    }

    @Test
    @DisplayName("대기 중인 영상이 없으면 추출기를 호출하지 않는다")
    void emptyQueueSkipsExtraction() {
        givenSlotAcquired();
        when(videos.claimNext()).thenReturn(Optional.empty());

        worker().processOne();

        verifyNoInteractions(extractor);
        verify(videos, never()).markDone(anyString(), anyString(), any(), anyString(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("RECIPE: 프론트 계약(ExtractedRecipe) 모양 그대로 저장 — 재료·단계·조리시간·버전")
    void recipeIsStoredInFrontendShape() {
        givenSlotAcquired();
        when(videos.claimNext()).thenReturn(Optional.of(row("설명란 원문")));
        when(extractor.extract(URL, "제목", "설명란 원문", null)).thenReturn(new RecipeExtractor.ExtractionResult(
                "RECIPE", "김치찌개", List.of("김치", "두부"), 20, List.of("끓인다"),
                null, List.of("김치찌개"), 300, List.of()));

        worker().processOne();

        ArgumentCaptor<Object> recipe = ArgumentCaptor.forClass(Object.class);
        verify(videos).markDone(eq(VIDEO_ID), eq("RECIPE"), recipe.capture(), eq(null),
                eq(List.of("김치찌개")), eq(RecipeExtractor.SUMMARY_VERSION), any());

        @SuppressWarnings("unchecked")
        Map<String, Object> saved = (Map<String, Object>) recipe.getValue();
        assertEquals("김치찌개", saved.get("name"));
        assertEquals(List.of("김치", "두부"), saved.get("ingredients"));
        assertEquals(20, saved.get("cookMinutes"));
        assertEquals(List.of("끓인다"), saved.get("steps"));
        assertEquals(RecipeExtractor.SUMMARY_VERSION, saved.get("summaryVersion"));
    }

    @Test
    @DisplayName("RECIPE: 재료를 사전에 upsert 하고 모델이 확신한 양념은 자동 확정한다 (5차-4 슬라이스1-C)")
    void recipeFeedsIngredientDictionary() {
        givenSlotAcquired();
        when(videos.claimNext()).thenReturn(Optional.of(row("설명란 원문")));
        when(extractor.extract(URL, "제목", "설명란 원문", null)).thenReturn(new RecipeExtractor.ExtractionResult(
                "RECIPE", "김치찌개", List.of("김치", "두부", "간장"), 20, List.of("끓인다"),
                null, List.of("김치찌개"), 300, List.of("간장")));

        worker().processOne();

        verify(dictionary).upsertPending(List.of("김치", "두부", "간장"));
        verify(dictionary).confirmSeasoningIfPending(List.of("간장"));
    }

    @Test
    @DisplayName("TIP/ETC: 레시피 JSON 은 저장하지 않고 요약·태그만 (1단계 기능은 RECIPE 만 사용)")
    void nonRecipeStoresSummaryOnly() {
        givenSlotAcquired();
        when(videos.claimNext()).thenReturn(Optional.of(row(null)));
        when(extractor.extract(URL, "제목", null, null)).thenReturn(new RecipeExtractor.ExtractionResult(
                "TIP", null, null, null, null, "신발끈 묶는 법", List.of("신발끈"), null, List.of()));

        worker().processOne();

        verify(videos).markDone(eq(VIDEO_ID), eq("TIP"), eq(null), eq("신발끈 묶는 법"),
                eq(List.of("신발끈")), eq(RecipeExtractor.SUMMARY_VERSION), any());
    }

    @Test
    @DisplayName("분석 신호는 원시 입력에서 도출돼 함께 저장된다 (pattern-raw-signal)")
    void analysisSignalsAreDerivedAndStored() {
        givenSlotAcquired();
        when(videos.claimNext()).thenReturn(Optional.of(row("설명란 원문")));
        when(extractor.extract(URL, "제목", "설명란 원문", null)).thenReturn(new RecipeExtractor.ExtractionResult(
                "RECIPE", "김치찌개", List.of("김치"), null, List.of("끓인다"), null, List.of(), 300, List.of()));

        worker().processOne();

        ArgumentCaptor<List<String>> signals = ArgumentCaptor.captor();
        verify(videos).markDone(anyString(), anyString(), any(), any(), any(), anyInt(), signals.capture());
        assertEquals(List.of("FRAMES", "DESCRIPTION", "TRANSCRIPT"), signals.getValue());
    }

    @Test
    @DisplayName("일시적 실패(429·503·타임아웃): 대기열 복귀 + 공통 백오프, 시도 횟수는 소모하지 않는다 (확정 결정)")
    void transientFailureBacksOffWithoutBurningAttempt() {
        givenSlotAcquired();
        when(videos.claimNext()).thenReturn(Optional.of(row(null)));
        when(extractor.extract(anyString(), any(), any(), any()))
                .thenThrow(new RecipeExtractor.TransientFailureException("429 한도"));

        worker().processOne();

        verify(videos).releaseAfterRateLimit(VIDEO_ID, "429 한도");
        verify(rateLimiter).backoff(anyInt()); // 전 인스턴스 공통 휴식
        verify(videos, never()).markFailure(anyString(), anyInt(), anyString()); // 실패로 세지 않음
    }

    @Test
    @DisplayName("진짜 실패(파싱 오류 등)는 시도 횟수를 소모해 3회 후 FAILED — 백오프는 걸지 않는다")
    void permanentFailureCountsAttempt() {
        givenSlotAcquired();
        when(videos.claimNext()).thenReturn(Optional.of(row(null)));
        when(extractor.extract(anyString(), any(), any(), any()))
                .thenThrow(new IllegalStateException("응답 파싱 실패"));

        worker().processOne();

        verify(videos).markFailure(VIDEO_ID, 3, "응답 파싱 실패");
        verify(videos, never()).releaseAfterRateLimit(anyString(), anyString());
        verify(rateLimiter, never()).backoff(anyInt());
    }

    @Test
    @DisplayName("워커가 죽어 ANALYZING 에 갇힌 영상 회수를 매 틱 시도한다")
    void requeuesStaleEveryTick() {
        givenSlotAcquired();
        when(videos.claimNext()).thenReturn(Optional.empty());

        worker().processOne();

        verify(videos).requeueStale();
    }

    @Test
    @DisplayName("이름 없는 RECIPE 응답도 저장은 된다 — null 이 프론트로 새지 않게 빈 문자열로")
    void recipeWithoutNameIsStoredSafely() {
        givenSlotAcquired();
        when(videos.claimNext()).thenReturn(Optional.of(row(null)));
        when(extractor.extract(anyString(), any(), any(), any())).thenReturn(new RecipeExtractor.ExtractionResult(
                "RECIPE", null, null, null, null, null, List.of(), null, List.of()));

        worker().processOne();

        ArgumentCaptor<Object> recipe = ArgumentCaptor.forClass(Object.class);
        verify(videos).markDone(anyString(), anyString(), recipe.capture(), any(), any(), anyInt(), any());

        @SuppressWarnings("unchecked")
        Map<String, Object> saved = (Map<String, Object>) recipe.getValue();
        assertEquals("", saved.get("name"));
        assertEquals(List.of(), saved.get("ingredients"));
        assertEquals(List.of(), saved.get("steps"));
        assertNull(saved.get("cookMinutes"));
    }

    @Test
    @DisplayName("신고 승격: old 재료 기록이 대기열 큐잉(분석 흔적 초기화)보다 먼저다 — 순서 불변식")
    void reportPromotionRecordsOldIngredientsBeforeQueueing() {
        givenSlotAcquired();
        when(videos.claimNext()).thenReturn(Optional.empty());
        when(reports.claimEligibleCase(anyInt(), anyInt()))
                .thenReturn(Optional.of(new IngredientReportRepository.ReportCase(VIDEO_ID, "쭈유(참기름)")));

        worker().processOne();

        var order = org.mockito.Mockito.inOrder(reports, videos);
        order.verify(reports).recordRunStart(VIDEO_ID, "쭈유(참기름)");
        order.verify(videos).queueReportReanalysis(VIDEO_ID, "쭈유(참기름)");
    }

    @Test
    @DisplayName("신고 재점검: 힌트가 추출기에 전달되고, 완료 시 신고 건이 마감된다")
    void reportReanalysisPassesHintAndCompletesCase() {
        givenSlotAcquired();
        when(videos.claimNext()).thenReturn(Optional.of(row("설명란 원문", "쭈유(참기름)")));
        when(extractor.extract(URL, "제목", "설명란 원문", "쭈유(참기름)"))
                .thenReturn(new RecipeExtractor.ExtractionResult(
                        "RECIPE", "야끼우동", List.of("쯔유"), 10, List.of("볶는다"),
                        null, List.of(), 300, List.of()));

        worker().processOne();

        verify(videos).markDone(eq(VIDEO_ID), eq("RECIPE"), any(), any(), any(), anyInt(), any());
        verify(reports).completeCase(VIDEO_ID, "쭈유(참기름)");
    }

    @Test
    @DisplayName("신고 재점검이 일시적 실패면 신고를 마감하지 않는다 — 백오프 후 재시도에 힌트가 유지돼야 함")
    void transientFailureKeepsReportOpen() {
        givenSlotAcquired();
        when(videos.claimNext()).thenReturn(Optional.of(row(null, "쭈유(참기름)")));
        when(extractor.extract(anyString(), any(), any(), any()))
                .thenThrow(new RecipeExtractor.TransientFailureException("503 과부하"));

        worker().processOne();

        verify(reports, never()).completeCase(anyString(), anyString());
    }

    @Test
    @DisplayName("신고 재점검이 FAILED 로 확정되면 신고 건을 마감한다 (diff 의 new 는 비어 실패가 보임)")
    void finalFailureClosesReportCase() {
        givenSlotAcquired();
        when(videos.claimNext()).thenReturn(Optional.of(row(null, "쭈유(참기름)")));
        when(extractor.extract(anyString(), any(), any(), any()))
                .thenThrow(new IllegalStateException("응답 파싱 실패"));
        when(videos.markFailure(anyString(), anyInt(), anyString())).thenReturn("FAILED");

        worker().processOne();

        verify(reports).completeCase(VIDEO_ID, "쭈유(참기름)");
    }
}
