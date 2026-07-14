// [AGENT] 사용자<->영상 연결 저장소 (2026-07-13 재편 — CONTEXT.md "영상 분석의 테이블 분리")
// 분석 결과·대기열·요청속도 제어는 VideoRepository 소관. 여기는 "누가 무엇을 등록했나"
// (user_id, video_id, registered_at)만 다루고, 화면용 조회는 video 와 조인해 응답 모양을
// 프론트 registrationTypes.ts 와 1:1로 유지한다 (분리로 인한 프론트 수정 없음이 성공 기준).
package com.chs.springboot.domain.recipe.registration;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class RegistrationRepository {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final VideoRepository videos;

    public RegistrationRepository(@Qualifier("gikkaJdbcClient") JdbcClient jdbc,
                                  @Qualifier("gikkaTxTemplate") TransactionTemplate tx,
                                  VideoRepository videos) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.videos = videos;
    }

    /** 화면용 조회 한 행 — video 와 조인된 모양. 프론트 RegistrationItem 과 1:1 (분리 전과 동일 계약) */
    public record Row(String videoId, String url, String platform, String category,
                      String status, String title, String thumbnailUrl, Integer durationSeconds,
                      String recipeJson, String summary, String tagsJson,
                      int attemptCount, OffsetDateTime registeredAt, String analysisSignalsJson) {
    }

    private static final String JOINED_COLUMNS =
            "v.video_id, v.url, v.platform, v.category, v.status, v.title, v.thumbnail_url, "
            + "v.duration_seconds, v.recipe_json, v.summary, v.tags, v.attempt_count, r.registered_at, "
            + "v.analysis_signals";
    private static final String JOIN = "FROM registration r JOIN video v ON v.video_id = r.video_id ";

    public List<Row> list(long userId) {
        return jdbc.sql("SELECT " + JOINED_COLUMNS + " " + JOIN
                        + "WHERE r.user_id = :userId ORDER BY r.registered_at DESC")
                .param("userId", userId)
                .query(this::mapRow).list();
    }

    public boolean exists(long userId, String videoId) {
        return jdbc.sql("SELECT COUNT(*) FROM registration WHERE user_id = :userId AND video_id = :videoId")
                .param("userId", userId).param("videoId", videoId)
                .query(Integer.class).single() > 0;
    }

    /**
     * 등록. 영상이 이미 있으면 연결만 추가(메타 조회·분석 0회 — CONTEXT.md 확정), 없으면
     * VideoRepository 가 새로 생성. 영상이 REMOVED(오너 삭제) 상태였다면 자동으로 되살림
     * (2026-07-13 확정 — 다시 등록되면 다시 볼 수 있어야 함). @return false = 이미 등록됨
     * (컨트롤러에서 409 — 호출 전 exists 로 선확인하는 게 정상 경로라 여기선 방어적 반환일 뿐).
     */
    public boolean registerLink(long userId, String videoId, Optional<VideoMetadataClient.VideoMetadata> meta) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            videos.insertIfAbsent(videoId, meta);
            videos.reviveIfRemoved(videoId, meta);
            return jdbc.sql("""
                            INSERT INTO registration (user_id, video_id) VALUES (:userId, :videoId)
                            ON CONFLICT (user_id, video_id) DO NOTHING
                            """)
                    .param("userId", userId).param("videoId", videoId).update() > 0;
        }));
    }

    /** 내 목록에서 지우기 — 내 연결만 삭제 (2026-07-14 확정). video·다른 사용자 연결은 그대로 유지. */
    public void delete(long userId, String videoId) {
        jdbc.sql("DELETE FROM registration WHERE user_id = :userId AND video_id = :videoId")
                .param("userId", userId).param("videoId", videoId).update();
    }

    public Optional<Row> find(long userId, String videoId) {
        return jdbc.sql("SELECT " + JOINED_COLUMNS + " " + JOIN
                        + "WHERE r.user_id = :userId AND r.video_id = :videoId")
                .param("userId", userId).param("videoId", videoId)
                .query(this::mapRow).optional();
    }

    /** 홈 섬네일: 분석 완료된 영상 전체 (2026-07-14 확정 — 레시피로 한정하지 않음.
        요리가 아니어도 "최근 분석된 것"을 다시 찾아 꺼내 쓸 수 있어야 한다는 앱의 기본 취지) */
    public List<Row> recentDone(long userId, int limit) {
        return jdbc.sql("SELECT " + JOINED_COLUMNS + " " + JOIN
                        + "WHERE r.user_id = :userId AND v.status = 'DONE' "
                        + "ORDER BY r.registered_at DESC LIMIT :limit")
                .param("userId", userId).param("limit", limit)
                .query(this::mapRow).list();
    }

    /** 추천 계산용: 완료된 요리(RECIPE+DONE)만 (2026-07-14 4차 확정 — recipe_json 이 항상 있음) */
    public List<Row> recipesForUser(long userId) {
        return jdbc.sql("SELECT " + JOINED_COLUMNS + " " + JOIN
                        + "WHERE r.user_id = :userId AND v.status = 'DONE' AND v.category = 'RECIPE' "
                        + "ORDER BY r.registered_at DESC")
                .param("userId", userId)
                .query(this::mapRow).list();
    }

    /** 모니터링 화면 한 행 — 전 사용자 대기열 실시간 추적용 (오너 전용) */
    public record MonitorRow(long userId, String email, String videoId, String url, String title,
                             String category, String status, Integer durationSeconds, int attemptCount,
                             String lastError, Integer analysisSeconds,
                             OffsetDateTime registeredAt, OffsetDateTime analyzingStartedAt,
                             int geminiRetryCount) {
    }

    /** 전 사용자 대기열 — 최신 등록이 앞. limit 필수(표시 개수 원본은 프론트 상수 하나뿐) */
    public List<MonitorRow> listForMonitor(int limit) {
        return jdbc.sql("""
                        SELECT r.user_id, u.email, v.video_id, v.url, v.title, v.category, v.status,
                               v.duration_seconds, v.attempt_count, v.last_error, v.analysis_seconds,
                               r.registered_at, v.analyzing_started_at, v.gemini_retry_count
                        FROM registration r
                            JOIN video v ON v.video_id = r.video_id
                            JOIN gikka_user u ON u.id = r.user_id
                        ORDER BY r.registered_at DESC
                        LIMIT :limit
                        """)
                .param("limit", limit)
                .query((rs, i) -> new MonitorRow(
                        rs.getLong("user_id"), rs.getString("email"), rs.getString("video_id"),
                        rs.getString("url"), rs.getString("title"), rs.getString("category"),
                        rs.getString("status"), rs.getObject("duration_seconds", Integer.class),
                        rs.getInt("attempt_count"), rs.getString("last_error"),
                        rs.getObject("analysis_seconds", Integer.class),
                        rs.getObject("registered_at", OffsetDateTime.class),
                        rs.getObject("analyzing_started_at", OffsetDateTime.class),
                        rs.getInt("gemini_retry_count")))
                .list();
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
                rs.getString("recipe_json"),
                rs.getString("summary"),
                rs.getString("tags"),
                rs.getInt("attempt_count"),
                rs.getObject("registered_at", OffsetDateTime.class),
                rs.getString("analysis_signals"));
    }
}
