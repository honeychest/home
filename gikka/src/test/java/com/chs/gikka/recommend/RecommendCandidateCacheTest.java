// [AGENT] 추천 공용 입력 캐시 검증 (2026-07-18) — DB·HTTP 없이 Mockito 로 저장소를 흉내 내고
// 주입 시계로 TTL 경과를 흉내 낸다. 핵심 불변식: "TTL 안에서는 몇 번을 요청해도 DB 재조회·
// 재파싱이 없다", "TTL 이 지나면 다시 읽는다", "파싱은 캐시 갱신 때 1회만 일어난다".
package com.chs.gikka.recommend;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.chs.gikka.dictionary.IngredientDictionaryRepository;
import com.chs.gikka.registration.VideoRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecommendCandidateCacheTest {

    /** 손으로 감는 시계 — TTL 경과를 실제 대기 없이 흉내 낸다 */
    private static final class SteppingClock extends Clock {
        private Instant now = Instant.parse("2026-07-18T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private VideoRepository videos;
    private IngredientDictionaryRepository dictionary;
    private SteppingClock clock;
    private RecommendCandidateCache cache;

    private static VideoRepository.RecipeCandidateRow row(String videoId, String recipeJson) {
        return new VideoRepository.RecipeCandidateRow(
                videoId, "https://youtu.be/" + videoId, videoId + "-title", "http://t/" + videoId, recipeJson);
    }

    @BeforeEach
    void setUp() {
        videos = mock(VideoRepository.class);
        dictionary = mock(IngredientDictionaryRepository.class);
        when(dictionary.matchKeys()).thenReturn(Map.of());
        when(dictionary.seasoningNames()).thenReturn(Set.of("고추장"));
        when(dictionary.basicNames()).thenReturn(Set.of());
        when(videos.allDoneRecipes()).thenReturn(List.of(
                row("a", "{\"name\":\"김치찌개\",\"ingredients\":[\"김치\",\"두부\"],"
                        + "\"cookMinutes\":15,\"steps\":[\"끓인다\"]}"),
                row("no-json", null)));
        clock = new SteppingClock();
        cache = new RecommendCandidateCache(videos, dictionary, clock);
    }

    @Test
    @DisplayName("TTL 안에서는 몇 번을 불러도 DB 재조회가 없다 — 요청당 비용이 개인 쿼리만 남는 근거")
    void servesFromCacheWithinTtl() {
        cache.get();
        cache.get();
        cache.get();

        verify(videos, times(1)).allDoneRecipes();
        verify(dictionary, times(1)).matchKeys();
    }

    @Test
    @DisplayName("TTL 이 지나면 다시 읽는다 — 새 분석 완료가 늦어도 TTL 안에 추천에 반영")
    void refreshesAfterTtl() {
        cache.get();
        clock.advance(RecommendCandidateCache.TTL.plusSeconds(1));
        cache.get();

        verify(videos, times(2)).allDoneRecipes();
    }

    @Test
    @DisplayName("파싱 결과가 Candidate 로 올바로 담긴다 — recipe_json 없는 행은 조용히 제외")
    void parsesCandidatesAndSkipsMissingJson() {
        var snapshot = cache.get();

        assertEquals(1, snapshot.candidates().size());
        var candidate = snapshot.candidates().get(0);
        assertEquals("a", candidate.videoId());
        assertEquals("김치찌개", candidate.title()); // 추출된 요리 이름이 title 자리에 실린다
        assertEquals(List.of("김치", "두부"), candidate.ingredients());
        assertEquals(15, candidate.cookMinutes());
        assertTrue(snapshot.dictionary().seasoningNames().contains("고추장"));
    }
}
