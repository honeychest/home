// [AGENT] 영상 분석·대기열 저장소 (2026-07-13 재편 — CONTEXT.md "영상 분석의 테이블 분리")
// video 테이블 = 분석 결과 원본(video_id 당 1행). 같은 영상이 여러 사용자에게 등록돼도
// 분석은 1회만 하도록 구조로 보장 — 대기열·워커·요청속도 제어가 전부 이 테이블 기준.
// gikka 전용 JdbcClient·TransactionTemplate 만 사용 (분리 규율 2·8).
package com.chs.springboot.domain.recipe.registration;

import java.time.OffsetDateTime;
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
    private final ObjectMapper mapper = new ObjectMapper();

    public VideoRepository(@Qualifier("gikkaJdbcClient") JdbcClient jdbc,
                           @Qualifier("gikkaTxTemplate") TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public record Row(String videoId, String url, String platform, String category, String status,
                      String title, String thumbnailUrl, Integer durationSeconds, String description,
                      String recipeJson, String summary, String tagsJson,
                      int attemptCount, Integer analysisSeconds, String lastError,
                      OffsetDateTime queuedAt) {
    }

    private static final String COLUMNS =
            "video_id, url, platform, category, status, title, thumbnail_url, duration_seconds, description, "
            + "recipe_json, summary, tags, attempt_count, analysis_seconds, last_error, queued_at";

    public boolean exists(String videoId) {
        return jdbc.sql("SELECT COUNT(*) FROM video WHERE video_id = :videoId")
                .param("videoId", videoId)
                .query(Integer.class).single() > 0;
    }

    /** 영상이 이미 있으면 아무 일도 안 함(메타 조회·분석 0회 — CONTEXT.md 확정). 없으면 새로 생성.
        description(설명란)은 재료가 원문으로 적힌 경우가 많아 분석 시 최우선 활용 (2026-07-13 확정) */
    public void insertIfAbsent(String videoId, Optional<VideoMetadataClient.VideoMetadata> meta,
                               int maxVideoMinutes) {
        String status = RegistrationRules.initialStatus(
                meta.map(VideoMetadataClient.VideoMetadata::durationSeconds).orElse(null), maxVideoMinutes);
        jdbc.sql("""
                        INSERT INTO video (video_id, url, status, title, thumbnail_url, duration_seconds,
                                            description)
                        VALUES (:videoId, :url, :status, :title, :thumb, :duration, :description)
                        ON CONFLICT (video_id) DO NOTHING
                        """)
                .param("videoId", videoId)
                .param("url", "https://www.youtube.com/watch?v=" + videoId)
                .param("status", status)
                .param("title", meta.map(VideoMetadataClient.VideoMetadata::title).orElse(null))
                .param("thumb", meta.map(VideoMetadataClient.VideoMetadata::thumbnailUrl).orElse(null))
                .param("duration", meta.map(VideoMetadataClient.VideoMetadata::durationSeconds).orElse(null))
                .param("description", meta.map(VideoMetadataClient.VideoMetadata::description).orElse(null))
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
                         String summaryOrNull, java.util.List<String> tags, int summaryVersion) {
        String json = toJsonOrNull(recipeOrNull, "레시피");
        String tagsJson = toJsonOrNull(tags == null || tags.isEmpty() ? null : tags, "태그");
        jdbc.sql("""
                        UPDATE video
                        SET status = 'DONE', category = :category, recipe_json = :json::jsonb,
                            summary = :summary, tags = :tags::jsonb, summary_version = :version,
                            analysis_seconds = EXTRACT(EPOCH FROM (now() - analyzing_started_at))::int,
                            analyzing_started_at = NULL, last_error = NULL
                        WHERE video_id = :videoId
                        """)
                .param("category", category).param("json", json)
                .param("summary", summaryOrNull).param("tags", tagsJson).param("version", summaryVersion)
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
                            analyzing_started_at = NULL, last_error = :error
                        WHERE video_id = :videoId
                        """)
                .param("max", maxAttempts).param("error", errorMessage)
                .param("videoId", videoId).update();
    }

    /** 429: 시도 횟수를 돌려주고 대기열 복귀 (기다렸다 재시도 — 횟수 소모 없음, 확정 결정) */
    public void releaseAfterRateLimit(String videoId) {
        jdbc.sql("""
                        UPDATE video
                        SET status = 'WAITING', attempt_count = GREATEST(attempt_count - 1, 0),
                            analyzing_started_at = NULL
                        WHERE video_id = :videoId
                        """)
                .param("videoId", videoId).update();
    }

    /** 재분석: 상태·분류·결과 초기화 후 대기열 맨 뒤로 (영상 단위 — 등록한 모든 계정에 반영, 확정 결정).
        @return false = 없는 영상 (404) */
    public boolean reanalyze(String videoId) {
        return jdbc.sql("""
                        UPDATE video
                        SET status = 'WAITING', category = NULL, recipe_json = NULL,
                            summary = NULL, tags = NULL, summary_version = NULL,
                            attempt_count = 0, analyzing_started_at = NULL, last_error = NULL,
                            analysis_seconds = NULL, queued_at = now()
                        WHERE video_id = :videoId
                        """)
                .param("videoId", videoId).update() > 0;
    }

    /**
     * 영상 삭제 (2026-07-13 확정 — 오너 전용, 원본이 유튜브에서 사라진 경우 등).
     * 영상정보(제목·썸네일·url)는 남기고 분석정보만 지워 REMOVED 로 표시 — 이 영상을 등록했던
     * 다른 사용자 목록에서도 흔적("삭제됨")이 보이게 한다(조용히 사라지지 않음).
     * registration 연결은 안 건드림. 재분석하면(사용자 재분석 버튼이든 재등록이든) 그냥
     * 평소 파이프라인을 다시 타므로 별도 복구 로직이 필요 없다.
     */
    public void remove(String videoId) {
        jdbc.sql("""
                        UPDATE video
                        SET status = 'REMOVED', category = NULL, recipe_json = NULL, summary = NULL,
                            tags = NULL, summary_version = NULL, attempt_count = 0,
                            analyzing_started_at = NULL, last_error = NULL, analysis_seconds = NULL
                        WHERE video_id = :videoId
                        """)
                .param("videoId", videoId).update();
    }

    /** REMOVED 상태였던 영상을 누군가 다시 등록하면 자동으로 되살림(WAITING) — insertIfAbsent 뒤에 항상 호출 */
    public void reviveIfRemoved(String videoId) {
        jdbc.sql("""
                        UPDATE video
                        SET status = 'WAITING', queued_at = now()
                        WHERE video_id = :videoId AND status = 'REMOVED'
                        """)
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

    /** Gemini 호출 슬롯 획득 — 인스턴스 합산 간격 하한 (원자적 UPDATE, 실패 = 이번 틱 쉼) */
    public boolean tryAcquireGeminiSlot(int minIntervalSeconds) {
        return jdbc.sql("""
                        UPDATE gemini_rate SET last_call_at = now()
                        WHERE id = 1 AND last_call_at <= now() - make_interval(secs => :interval)
                        """)
                .param("interval", minIntervalSeconds).update() > 0;
    }

    /** 429 후 전 인스턴스 공통 휴식 (last_call_at 을 미래로 밀어 양쪽 다 멈춤) + 발생 이력 기록 */
    public void backoffGemini(int seconds) {
        jdbc.sql("""
                        UPDATE gemini_rate
                        SET last_call_at = now() + make_interval(secs => :secs),
                            rate_limit_count = rate_limit_count + 1, last_rate_limited_at = now()
                        WHERE id = 1
                        """)
                .param("secs", seconds).update();
    }

    /** 워커 생존 신호 — 일할 게 없어도 틱마다 갱신 (last_call_at 은 429 로 미래로 밀리기도 해서 별도 분리) */
    public void touchHeartbeat() {
        jdbc.sql("UPDATE gemini_rate SET heartbeat_at = now() WHERE id = 1").update();
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

    /** 워커 생존·429 이력 — gemini_rate 단일 행 */
    public record WorkerStatus(OffsetDateTime heartbeatAt, int rateLimitCount, OffsetDateTime lastRateLimitedAt) {
    }

    public WorkerStatus workerStatus() {
        return jdbc.sql("SELECT heartbeat_at, rate_limit_count, last_rate_limited_at "
                        + "FROM gemini_rate WHERE id = 1")
                .query((rs, i) -> new WorkerStatus(
                        rs.getObject("heartbeat_at", OffsetDateTime.class),
                        rs.getInt("rate_limit_count"),
                        rs.getObject("last_rate_limited_at", OffsetDateTime.class)))
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
                rs.getObject("queued_at", OffsetDateTime.class));
    }
}
