// [AGENT] 영상 분석·대기열 저장소 (2026-07-13 재편 — CONTEXT.md "영상 분석의 테이블 분리")
// video 테이블 = 분석 결과 원본(video_id 당 1행). 같은 영상이 여러 사용자에게 등록돼도
// 분석은 1회만 하도록 구조로 보장 — 대기열·워커가 이 테이블 기준.
// Gemini 요청속도 제어·워커 생존 신호는 gemini_rate 테이블이라 GeminiRateLimiter 소관
// (2026-07-15 분리 — 여기 SQL 은 전부 video 테이블만 건드린다).
// gikka 전용 JdbcClient·TransactionTemplate 만 사용 (분리 규율 2·8).
package com.chs.springboot.domain.recipe.registration;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class VideoRepository {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final GikkaMediaProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();

    public VideoRepository(@Qualifier("gikkaJdbcClient") JdbcClient jdbc,
                           @Qualifier("gikkaTxTemplate") TransactionTemplate tx,
                           GikkaMediaProperties properties) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.properties = properties;
    }

    public record Row(String videoId, String url, String platform, String category, String status,
                      String title, String thumbnailUrl, Integer durationSeconds, String description,
                      String recipeJson, String summary, String tagsJson,
                      int attemptCount, Integer analysisSeconds, String lastError,
                      OffsetDateTime queuedAt, String analysisSignalsJson, int geminiRetryCount) {
    }

    private static final String COLUMNS =
            "video_id, url, platform, category, status, title, thumbnail_url, duration_seconds, description, "
            + "recipe_json, summary, tags, attempt_count, analysis_seconds, last_error, queued_at, "
            + "analysis_signals, gemini_retry_count";

    /**
     * "분석이 남긴 것을 전부 지운다" 의 유일한 정의 — 재분석과 영상 삭제가 이걸 함께 쓴다
     * (두 동작은 목적이 다르지만 "분석 흔적을 없앤다"는 부분은 정확히 같다).
     * 분석 결과 컬럼을 새로 만들면 여기 한 줄만 더하면 양쪽에 함께 반영된다.
     * (2026-07-15 통합 — 이전엔 두 메서드가 각자 11개 컬럼을 손으로 나열해, V9·V10 때
     * 새 컬럼을 양쪽에 손으로 끼워넣어야 했다. 하나라도 빠뜨리면 낡은 분석 결과가 조용히
     * 남는데 그걸 잡아줄 장치가 없었음.)
     * 주의: markDone·markFailure·releaseAfterRateLimit 은 여기 안 쓴다 — 그쪽은 "분석 결과를
     * 지우는 것"이 아니라 "한 번의 시도를 끝내는" 다른 개념이다 (결과는 오히려 남긴다).
     */
    private static final String CLEAR_ANALYSIS = """
            category = NULL, recipe_json = NULL, summary = NULL, tags = NULL,
            summary_version = NULL, analysis_signals = NULL, analysis_seconds = NULL,
            attempt_count = 0, analyzing_started_at = NULL, last_error = NULL,
            gemini_retry_count = 0
            """;

    /**
     * 메타(제목·썸네일·길이·설명란) 갱신 — 메타 조회 실패로 값이 없으면 기존 값 유지(COALESCE),
     * 즉 조회 실패가 재분석·복구를 막지 않는다. bindMetadata 와 짝이다(파라미터 이름이 걸려 있음).
     * (2026-07-15 통합 — 이전엔 신규 등록·재분석·복구 세 SQL 이 각자 같은 4줄을 반복했고,
     * 실제로 V8[description] 때 재분석 경로에 이 갱신을 빠뜨린 채 배포돼 "재분석해도 설명란이
     * 계속 비어 있다"를 운영에서 발견했다. 이제 메타 필드를 늘릴 때 고칠 곳은
     * VideoMetadata · 이 조각 · bindMetadata · INSERT 문뿐이다.)
     */
    private static final String MERGE_METADATA = """
            title = COALESCE(:title, title), thumbnail_url = COALESCE(:thumb, thumbnail_url),
            duration_seconds = COALESCE(:duration, duration_seconds),
            description = COALESCE(:description, description)
            """;

    private static JdbcClient.StatementSpec bindMetadata(
            JdbcClient.StatementSpec spec, Optional<VideoMetadataClient.VideoMetadata> meta) {
        return spec
                .param("title", meta.map(VideoMetadataClient.VideoMetadata::title).orElse(null))
                .param("thumb", meta.map(VideoMetadataClient.VideoMetadata::thumbnailUrl).orElse(null))
                .param("duration", meta.map(VideoMetadataClient.VideoMetadata::durationSeconds).orElse(null))
                .param("description", meta.map(VideoMetadataClient.VideoMetadata::description).orElse(null));
    }

    /** 길이 컷 판정 — 메타를 새로 받는 세 경로(신규 등록·재분석·REMOVED 복구)가 같은 규칙을 쓴다 */
    private String statusFor(Optional<VideoMetadataClient.VideoMetadata> meta) {
        return RegistrationRules.initialStatus(
                meta.map(VideoMetadataClient.VideoMetadata::durationSeconds).orElse(null),
                properties.getMaxVideoMinutes());
    }

    /**
     * 메타 조회 생략 여부 판단용 (2026-07-13 확정 — REMOVED 는 "존재하지만 되살아나야 할" 상태라
     * exists() 만 보면 메타를 영원히 못 채움). REMOVED 는 존재하지 않는 것처럼 취급해 메타를
     * 다시 조회하게 한다.
     */
    public boolean existsActive(String videoId) {
        return jdbc.sql("SELECT COUNT(*) FROM video WHERE video_id = :videoId AND status <> 'REMOVED'")
                .param("videoId", videoId)
                .query(Integer.class).single() > 0;
    }

    /** 추천 후보 전용 슬림 행 — 추천이 실제로 쓰는 5컬럼만 (2026-07-18 성능 점검). */
    public record RecipeCandidateRow(String videoId, String url, String title, String thumbnailUrl,
                                     String recipeJson) {
    }

    /** 추천 후보 풀 — gikka 전체의 완료된 요리(DONE+RECIPE). 사용자 무관(누구 것이든)이다:
        "내 보관함" 여부는 컨트롤러가 registration 으로 따로 표시한다 (2026-07-16 5차, 콜드스타트
        대응 — 등록 몇 개 안 되는 신규 사용자도 전체 풀에서 추천이 뜨게).
        (2026-07-18 슬림화) COLUMNS 전체를 실으면 추천에 안 쓰는 description(설명란 원문) 등이
        후보 수에 비례해 운반돼 낭비가 컸다. 정렬도 뺐다 — 순서는 RecommendRules.bucket 이 다시 정한다. */
    public List<RecipeCandidateRow> allDoneRecipes() {
        return jdbc.sql("SELECT video_id, url, title, thumbnail_url, recipe_json FROM video "
                        + "WHERE status = 'DONE' AND category = 'RECIPE'")
                .query((rs, i) -> new RecipeCandidateRow(
                        rs.getString("video_id"), rs.getString("url"), rs.getString("title"),
                        rs.getString("thumbnail_url"), rs.getString("recipe_json")))
                .list();
    }

    /** 영상이 이미 있으면 아무 일도 안 함(메타 조회·분석 0회 — CONTEXT.md 확정). 없으면 새로 생성.
        description(설명란)은 재료가 원문으로 적힌 경우가 많아 분석 시 최우선 활용 (2026-07-13 확정) */
    public void insertIfAbsent(String videoId, Optional<VideoMetadataClient.VideoMetadata> meta) {
        bindMetadata(jdbc.sql("""
                        INSERT INTO video (video_id, url, status, title, thumbnail_url, duration_seconds,
                                            description)
                        VALUES (:videoId, :url, :status, :title, :thumb, :duration, :description)
                        ON CONFLICT (video_id) DO NOTHING
                        """), meta)
                .param("videoId", videoId)
                .param("url", "https://www.youtube.com/watch?v=" + videoId)
                .param("status", statusFor(meta))
                .update();
    }

    /* ── 워커 전용 (인스턴스 2개 안전: SKIP LOCKED 로 한 항목은 한 워커만 잡는다) ── */

    /** 다음 대기 영상을 원자적으로 집어 ANALYZING 으로 전환 (queued_at 기준 FIFO). 없으면 empty */
    public Optional<Row> claimNext() {
        return tx.execute(status -> jdbc.sql("""
                        UPDATE video
                        SET status = 'ANALYZING', attempt_count = attempt_count + 1,
                            analyzing_started_at = now()
                        WHERE video_id = (
                            SELECT video_id FROM video
                            WHERE status = 'WAITING'
                            ORDER BY queued_at ASC
                            LIMIT 1
                            FOR UPDATE SKIP LOCKED
                        )
                        RETURNING """ + " " + COLUMNS)
                .query(this::mapRow).optional());
    }

    public void markDone(String videoId, String category, Object recipeOrNull,
                         String summaryOrNull, java.util.List<String> tags, int summaryVersion,
                         java.util.List<String> analysisSignals) {
        String json = toJsonOrNull(recipeOrNull, "레시피");
        String tagsJson = toJsonOrNull(tags == null || tags.isEmpty() ? null : tags, "태그");
        String signalsJson = toJsonOrNull(analysisSignals == null || analysisSignals.isEmpty()
                ? null : analysisSignals, "분석 신호");
        jdbc.sql("""
                        UPDATE video
                        SET status = 'DONE', category = :category, recipe_json = :json::jsonb,
                            summary = :summary, tags = :tags::jsonb, summary_version = :version,
                            analysis_signals = :signals::jsonb,
                            analysis_seconds = EXTRACT(EPOCH FROM (now() - analyzing_started_at))::int,
                            analyzing_started_at = NULL, last_error = NULL, gemini_retry_count = 0
                        WHERE video_id = :videoId
                        """)
                .param("category", category).param("json", json)
                .param("summary", summaryOrNull).param("tags", tagsJson).param("version", summaryVersion)
                .param("signals", signalsJson)
                .param("videoId", videoId).update();
    }

    private String toJsonOrNull(Object value, String what) {
        try {
            return value == null ? null : mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(what + " 직렬화 실패", e);
        }
    }

    /** 진짜 실패: 3회 소진 시 FAILED, 아니면 대기열로 복귀. errorMessage 는 모니터링 화면 노출용 */
    public void markFailure(String videoId, int maxAttempts, String errorMessage) {
        jdbc.sql("""
                        UPDATE video
                        SET status = CASE WHEN attempt_count >= :max THEN 'FAILED' ELSE 'WAITING' END,
                            analysis_seconds = EXTRACT(EPOCH FROM (now() - analyzing_started_at))::int,
                            analyzing_started_at = NULL, last_error = :error, gemini_retry_count = 0
                        WHERE video_id = :videoId
                        """)
                .param("max", maxAttempts).param("error", errorMessage)
                .param("videoId", videoId).update();
    }

    /** 429·503·타임아웃: 시도 횟수를 돌려주고 대기열 복귀 (기다렸다 재시도 — 횟수 소모 없음, 확정 결정).
        이 경로는 HybridRecipeExtractor 가 대체할 로컬 결과를 못 찾았을 때만 타므로(2026-07-14 확정),
        gemini_retry_count 를 그대로 누적해 모니터링 화면에 "몇 번째 재시도인지" 보여준다.
        errorMessage 는 그동안 last_error 가 안 채워지던 문제 해결 — 무슨 오류로 대기 중인지 노출 */
    public void releaseAfterRateLimit(String videoId, String errorMessage) {
        jdbc.sql("""
                        UPDATE video
                        SET status = 'WAITING', attempt_count = GREATEST(attempt_count - 1, 0),
                            analyzing_started_at = NULL, last_error = :error,
                            gemini_retry_count = gemini_retry_count + 1
                        WHERE video_id = :videoId
                        """)
                .param("videoId", videoId).param("error", errorMessage).update();
    }

    /**
     * 재분석: 상태·분류·결과 초기화 후 대기열 맨 뒤로 (영상 단위 — 등록한 모든 계정에 반영, 확정 결정).
     * 메타(제목·썸네일·길이·설명란)도 함께 갱신 (2026-07-13 확정 — 실측 발견: description 활용
     * 기능 도입 전에 등록된 영상은 재분석해도 description 이 계속 NULL이라 새 기능이 무용지물이었음.
     * meta 필드가 없으면(조회 실패 등) 기존 값 유지(COALESCE) — 메타 조회 실패가 재분석을 막지 않음.
     * 길이 컷도 새 duration 기준으로 재판정(과거엔 무조건 WAITING — 갱신된 길이가 7분 초과로 바뀌는
     * 경우까지 반영). @return false = 없는 영상 (404)
     */
    public boolean reanalyze(String videoId, Optional<VideoMetadataClient.VideoMetadata> meta) {
        return bindMetadata(jdbc.sql(
                        "UPDATE video SET status = :status, queued_at = now(), "
                        + CLEAR_ANALYSIS + ", " + MERGE_METADATA
                        + " WHERE video_id = :videoId"), meta)
                .param("status", statusFor(meta))
                .param("videoId", videoId).update() > 0;
    }

    /**
     * 영상 삭제 (2026-07-13 확정 — 오너 전용, 원본이 유튜브에서 사라진 경우 등).
     * 영상정보(제목·썸네일·url)는 남기고 분석정보만 지워 REMOVED 로 표시 — 이 영상을 등록했던
     * 다른 사용자 목록에서도 흔적("삭제됨")이 보이게 한다(조용히 사라지지 않음).
     * registration 연결은 안 건드림. 재분석하면(사용자 재분석 버튼이든 재등록이든) 그냥
     * 평소 파이프라인을 다시 타므로 별도 복구 로직이 필요 없다.
     * @return false = 없는 영상 (404)
     */
    public boolean remove(String videoId) {
        return jdbc.sql("UPDATE video SET status = 'REMOVED', " + CLEAR_ANALYSIS
                        + " WHERE video_id = :videoId")
                .param("videoId", videoId).update() > 0;
    }

    /**
     * REMOVED 상태였던 영상을 누군가 다시 등록하면 자동으로 되살림(WAITING) — insertIfAbsent 뒤에
     * 항상 호출. 메타도 함께 갱신(2026-07-13 확정 — reanalyze 와 같은 이유: 되살릴 때 메타를
     * 안 갱신하면 description 등이 계속 비어있을 수 있음). meta 는 컨트롤러가 existsActive() 로
     * REMOVED 를 "신규"처럼 취급해 미리 조회해 온 것 — 조회 실패 시 기존 값 유지(COALESCE).
     */
    public void reviveIfRemoved(String videoId, Optional<VideoMetadataClient.VideoMetadata> meta) {
        bindMetadata(jdbc.sql(
                        "UPDATE video SET status = :status, queued_at = now(), " + MERGE_METADATA
                        + " WHERE video_id = :videoId AND status = 'REMOVED'"), meta)
                .param("status", statusFor(meta))
                .param("videoId", videoId).update();
    }

    /** 워커가 죽어 ANALYZING 에 갇힌 영상 회수 (10분 기준) */
    public int requeueStale() {
        return jdbc.sql("""
                        UPDATE video
                        SET status = 'WAITING', analyzing_started_at = NULL
                        WHERE status = 'ANALYZING' AND analyzing_started_at < now() - interval '10 minutes'
                        """)
                .update();
    }

    /** 대기열 크기 요약 — LIMIT 없는 전체 카운트 (모니터링 "밀려서 느린가" 판단용) */
    public record QueueCounts(int waiting, int analyzing) {
    }

    public QueueCounts queueCounts() {
        return jdbc.sql("""
                        SELECT
                            COUNT(*) FILTER (WHERE status = 'WAITING') AS waiting,
                            COUNT(*) FILTER (WHERE status = 'ANALYZING') AS analyzing
                        FROM video
                        """)
                .query((rs, i) -> new QueueCounts(rs.getInt("waiting"), rs.getInt("analyzing")))
                .single();
    }

    private Row mapRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Row(
                rs.getString("video_id"),
                rs.getString("url"),
                rs.getString("platform"),
                rs.getString("category"),
                rs.getString("status"),
                rs.getString("title"),
                rs.getString("thumbnail_url"),
                rs.getObject("duration_seconds", Integer.class),
                rs.getString("description"),
                rs.getString("recipe_json"),
                rs.getString("summary"),
                rs.getString("tags"),
                rs.getInt("attempt_count"),
                rs.getObject("analysis_seconds", Integer.class),
                rs.getString("last_error"),
                rs.getObject("queued_at", OffsetDateTime.class),
                rs.getString("analysis_signals"),
                rs.getInt("gemini_retry_count"));
    }
}
