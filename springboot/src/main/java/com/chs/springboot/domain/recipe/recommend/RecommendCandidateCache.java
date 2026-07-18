// [AGENT] 추천 공용 입력 캐시 (2026-07-18 — 100명 동시 사용 기준 안정화).
// 캐시하는 것은 "전 사용자 공통"인 매칭 입력 둘뿐이다: 후보 목록(recipe_json 파싱 결과)과
// 재료 사전 3종. 사용자별 입력(냉장고·보관함)과 매칭 결과는 캐시하지 않는다 — 조건별로 달라
// 캐시하면 틀린 답이 된다. 이 캐시로 "요청마다 전 후보 조회+파싱"이 인스턴스당 TTL 에 1회로 준다.
// 만료를 만난 요청 중 하나만 갱신하고 나머지는 직전 스냅샷을 그대로 쓴다(stale-while-revalidate —
// 동시 100 요청이 만료를 만나도 재조회·재파싱은 1회, 나머지는 대기 없음). 첫 적재만 전원 대기.
// 인스턴스별 캐시 — 앱 2인스턴스가 각자 들고 최대 TTL 만큼 어긋나는 건 무해(새 분석 완료가
// 추천에 늦게 뜨는 신선도 문제일 뿐, 정합성 문제가 아님). 공유 상태를 인스턴스 메모리에 두지
// 말라는 규칙(AGENTS.md)의 예외가 아니라 — 이건 "공유 상태"가 아니라 DB 원본의 읽기 사본이다.
// 메모리 인지(서버 프로파일 원칙): 후보 24,000개 기준 인스턴스당 ~12-24MB 상주 — 힙 512M 에서
// 수용 가능. 후보가 수십만이 되면 재료 정규화 테이블(DB측 매칭)로 전환 재검토.
package com.chs.springboot.domain.recipe.recommend;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import com.chs.springboot.domain.recipe.registration.IngredientDictionaryRepository;
import com.chs.springboot.domain.recipe.registration.VideoRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RecommendCandidateCache {

    /** 신선도 조절 장치(부하 조절이 아님 — 갱신 비용은 이미 TTL당 1회뿐이다). 늘리면 새 분석
        완료가 추천에 그만큼 늦게 뜬다 (2026-07-18 사용자 논의 — 60초 확정). */
    static final Duration TTL = Duration.ofSeconds(60);

    /** 매칭의 전 사용자 공통 입력 — 늘 함께 갱신돼야 해서 한 덩어리다 (사전만 새것이면
        후보의 파싱 시점과 어긋날 수 있으나 어느 쪽도 60초 내 어긋남이라 무해). */
    public record Snapshot(List<RecommendRules.Candidate> candidates,
                           RecommendRules.Dictionary dictionary) {
    }

    private record Timed(Snapshot snapshot, Instant expiresAt) {
    }

    private final VideoRepository videos;
    private final IngredientDictionaryRepository dictionary;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<Timed> current = new AtomicReference<>();
    private final ReentrantLock refreshLock = new ReentrantLock();

    @Autowired
    public RecommendCandidateCache(VideoRepository videos, IngredientDictionaryRepository dictionary) {
        this(videos, dictionary, Clock.systemDefaultZone());
    }

    /** 테스트 시임 — 시계를 주입해 TTL 경과를 흉내 낸다 */
    RecommendCandidateCache(VideoRepository videos, IngredientDictionaryRepository dictionary, Clock clock) {
        this.videos = videos;
        this.dictionary = dictionary;
        this.clock = clock;
    }

    public Snapshot get() {
        Timed timed = current.get();
        if (isFresh(timed)) {
            return timed.snapshot();
        }
        if (refreshLock.tryLock()) {
            try {
                Timed again = current.get(); // 락 대기 사이 다른 요청이 이미 갱신했을 수 있다
                if (isFresh(again)) {
                    return again.snapshot();
                }
                return refresh();
            } finally {
                refreshLock.unlock();
            }
        }
        if (timed != null) {
            return timed.snapshot(); // 다른 요청이 갱신 중 — 직전 스냅샷으로 대기 없이 응답
        }
        refreshLock.lock(); // 첫 적재만은 기다린다 (보여줄 직전 값이 없다)
        try {
            Timed again = current.get();
            return again != null ? again.snapshot() : refresh();
        } finally {
            refreshLock.unlock();
        }
    }

    private boolean isFresh(Timed timed) {
        return timed != null && Instant.now(clock).isBefore(timed.expiresAt());
    }

    private Snapshot refresh() {
        var dict = new RecommendRules.Dictionary(
                dictionary.matchKeys(), dictionary.seasoningNames(), dictionary.basicNames());
        List<RecommendRules.Candidate> candidates = videos.allDoneRecipes().stream()
                .map(this::toCandidate)
                .flatMap(Optional::stream)
                .toList();
        Snapshot snapshot = new Snapshot(candidates, dict);
        current.set(new Timed(snapshot, Instant.now(clock).plus(TTL)));
        return snapshot;
    }

    /** recipe_json 이 없으면(이론상 RECIPE+DONE 인데 비어있는 이상 상태) 후보에서 조용히 제외
        (RecommendController 에서 이관 — 2026-07-18) */
    private Optional<RecommendRules.Candidate> toCandidate(VideoRepository.RecipeCandidateRow row) {
        if (row.recipeJson() == null) {
            return Optional.empty();
        }
        JsonNode node;
        try {
            node = mapper.readTree(row.recipeJson());
        } catch (Exception e) {
            throw new IllegalStateException("recipe_json 파싱 실패: video=" + row.videoId(), e);
        }
        String name = node.hasNonNull("name") && !node.get("name").asText().isBlank()
                ? node.get("name").asText() : row.title();
        List<String> ingredients = new ArrayList<>();
        node.path("ingredients").forEach(n -> ingredients.add(n.asText()));
        Integer cookMinutes = node.hasNonNull("cookMinutes") ? node.get("cookMinutes").asInt() : null;
        List<String> steps = new ArrayList<>();
        node.path("steps").forEach(n -> steps.add(n.asText()));
        return Optional.of(new RecommendRules.Candidate(
                row.videoId(), row.url(), name, row.thumbnailUrl(), ingredients, cookMinutes, steps));
    }
}
