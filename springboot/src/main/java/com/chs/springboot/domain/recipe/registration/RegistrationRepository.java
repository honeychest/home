// [AGENT] 등록·대기열 저장소 — SQL·트랜잭션·동작 규칙 집중 (서비스 계층 없음, PLAYBOOK 관례 3)
// gikka 전용 JdbcClient·TransactionTemplate 만 사용 (분리 규율 2·8).
// 응답 모양은 프론트 registrationTypes.ts (미래 API 응답)와 1:1.
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
public class RegistrationRepository {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper = new ObjectMapper();

    public RegistrationRepository(@Qualifier("gikkaJdbcClient") JdbcClient jdbc,
                                  @Qualifier("gikkaTxTemplate") TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public record Row(long userId, String videoId, String url, String platform, String category,
                      String status, String title, String thumbnailUrl, Integer durationSeconds,
                      String recipeJson, String summary, String tagsJson,
                      int attemptCount, OffsetDateTime registeredAt) {
    }

    private static final String COLUMNS =
            "user_id, video_id, url, platform, category, status, title, thumbnail_url, "
            + "duration_seconds, recipe_json, summary, tags, attempt_count, registered_at";

    public List<Row> list(long userId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM registration WHERE user_id = :userId "
                        + "ORDER BY registered_at DESC")
                .param("userId", userId)
                .query(this::mapRow).list();
    }

    public boolean exists(long userId, String videoId) {
        return jdbc.sql("SELECT COUNT(*) FROM registration WHERE user_id = :userId AND video_id = :videoId")
                .param("userId", userId).param("videoId", videoId)
                .query(Integer.class).single() > 0;
    }

    /** 등록. 이미 있으면 false (중복 — 컨트롤러에서 409). status 는 길이 컷 결과에 따라 WAITING/TOO_LONG */
    public boolean insert(long userId, String videoId, String url, String status,
                          String title, String thumbnailUrl, Integer durationSeconds) {
        return jdbc.sql("""
                        INSERT INTO registration
                            (user_id, video_id, url, status, title, thumbnail_url, duration_seconds)
                        VALUES (:userId, :videoId, :url, :status, :title, :thumb, :duration)
                        ON CONFLICT (user_id, video_id) DO NOTHING
                        """)
                .param("userId", userId).param("videoId", videoId).param("url", url)
                .param("status", status).param("title", title).param("thumb", thumbnailUrl)
                .param("duration", durationSeconds)
                .update() > 0;
    }

    public Optional<Row> find(long userId, String videoId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM registration "
                        + "WHERE user_id = :userId AND video_id = :videoId")
                .param("userId", userId).param("videoId", videoId)
                .query(this::mapRow).optional();
    }

    /** 재분석: 상태·분류·결과 초기화 후 대기열 맨 뒤로. @return false = 없는 항목 (404) */
    public boolean reanalyze(long userId, String videoId) {
        return jdbc.sql("""
                        UPDATE registration
                        SET status = 'WAITING', category = NULL, recipe_json = NULL,
                            summary = NULL, tags = NULL, summary_version = NULL,
                            attempt_count = 0, analyzing_started_at = NULL, registered_at = now()
                        WHERE user_id = :userId AND video_id = :videoId
                        """)
                .param("userId", userId).param("videoId", videoId).update() > 0;
    }

    /** 홈 섬네일: 분석 완료된 요리만 */
    public List<Row> recentDone(long userId, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM registration "
                        + "WHERE user_id = :userId AND status = 'DONE' AND category = 'RECIPE' "
                        + "ORDER BY registered_at DESC LIMIT :limit")
                .param("userId", userId).param("limit", limit)
                .query(this::mapRow).list();
    }

    /* ── 워커 전용 (인스턴스 2개 안전: SKIP LOCKED 로 한 항목은 한 워커만 잡는다) ── */

    /** 다음 대기 항목을 원자적으로 집어 ANALYZING 으로 전환 (FIFO). 없으면 empty */
    public Optional<Row> claimNext() {
        return tx.execute(status -> jdbc.sql("""
                        UPDATE registration
                        SET status = 'ANALYZING', attempt_count = attempt_count + 1,
                            analyzing_started_at = now()
                        WHERE (user_id, video_id) = (
                            SELECT user_id, video_id FROM registration
                            WHERE status = 'WAITING'
                            ORDER BY registered_at ASC
                            LIMIT 1
                            FOR UPDATE SKIP LOCKED
                        )
                        RETURNING """ + " " + COLUMNS)
                .query(this::mapRow).optional());
    }

    public void markDone(long userId, String videoId, String category, Object recipeOrNull,
                         String summaryOrNull, List<String> tags, int summaryVersion) {
        String json = toJsonOrNull(recipeOrNull, "레시피");
        String tagsJson = toJsonOrNull(tags == null || tags.isEmpty() ? null : tags, "태그");
        jdbc.sql("""
                        UPDATE registration
                        SET status = 'DONE', category = :category, recipe_json = :json::jsonb,
                            summary = :summary, tags = :tags::jsonb, summary_version = :version,
                            analyzing_started_at = NULL
                        WHERE user_id = :userId AND video_id = :videoId
                        """)
                .param("category", category).param("json", json)
                .param("summary", summaryOrNull).param("tags", tagsJson).param("version", summaryVersion)
                .param("userId", userId).param("videoId", videoId).update();
    }

    private String toJsonOrNull(Object value, String what) {
        try {
            return value == null ? null : mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(what + " 직렬화 실패", e);
        }
    }

    /** 진짜 실패: 3회 소진 시 FAILED, 아니면 대기열로 복귀 */
    public void markFailure(long userId, String videoId, int maxAttempts) {
        jdbc.sql("""
                        UPDATE registration
                        SET status = CASE WHEN attempt_count >= :max THEN 'FAILED' ELSE 'WAITING' END,
                            analyzing_started_at = NULL
                        WHERE user_id = :userId AND video_id = :videoId
                        """)
                .param("max", maxAttempts)
                .param("userId", userId).param("videoId", videoId).update();
    }

    /** 429: 시도 횟수를 돌려주고 대기열 복귀 (기다렸다 재시도 — 횟수 소모 없음, 확정 결정) */
    public void releaseAfterRateLimit(long userId, String videoId) {
        jdbc.sql("""
                        UPDATE registration
                        SET status = 'WAITING', attempt_count = GREATEST(attempt_count - 1, 0),
                            analyzing_started_at = NULL
                        WHERE user_id = :userId AND video_id = :videoId
                        """)
                .param("userId", userId).param("videoId", videoId).update();
    }

    /** 워커가 죽어 ANALYZING 에 갇힌 항목 회수 (10분 기준) */
    public int requeueStale() {
        return jdbc.sql("""
                        UPDATE registration
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

    /** 429 후 전 인스턴스 공통 휴식 (last_call_at 을 미래로 밀어 양쪽 다 멈춤) */
    public void backoffGemini(int seconds) {
        jdbc.sql("UPDATE gemini_rate SET last_call_at = now() + make_interval(secs => :secs) WHERE id = 1")
                .param("secs", seconds).update();
    }

    private Row mapRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Row(
                rs.getLong("user_id"),
                rs.getString("video_id"),
                rs.getString("url"),
                rs.getString("platform"),
                rs.getString("category"),
                rs.getString("status"),
                rs.getString("title"),
                rs.getString("thumbnail_url"),
                rs.getObject("duration_seconds", Integer.class),
                rs.getString("recipe_json"),
                rs.getString("summary"),
                rs.getString("tags"),
                rs.getInt("attempt_count"),
                rs.getObject("registered_at", OffsetDateTime.class));
    }
}
