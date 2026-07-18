// [AGENT] 사용자<->영상 연결 저장소 (2026-07-13 재편 — CONTEXT.md "영상 분석의 테이블 분리")
// 분석 결과·대기열·요청속도 제어는 VideoRepository 소관. 여기는 "누가 무엇을 등록했나"
// (user_id, video_id, registered_at)만 다루고, 화면용 조회는 video 와 조인해 응답 모양을
// 프론트 registrationTypes.ts 와 1:1로 유지한다 (분리로 인한 프론트 수정 없음이 성공 기준).
package com.chs.springboot.domain.recipe.registration;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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

    /** 내가 등록한 영상 ID 전부 — 추천에서 "내 보관함" 여부 표시용 (2026-07-16 5차, 추천 소스가
        gikka 전체로 확장되며 신설. 구 recipesForUser 는 후보 소스가 VideoRepository.allDoneRecipes 로
        옮겨가 유일 호출부가 사라져 제거함). */
    public List<String> videoIdsForUser(long userId) {
        return jdbc.sql("SELECT video_id FROM registration WHERE user_id = :userId")
                .param("userId", userId)
                .query(String.class).list();
    }

    /** 보관함 검색 매칭 조건 (2026-07-16 5차) — 제목·추출된 요리 이름·검색 태그에 부분일치(대소문자 무시).
        tags 는 JSONB 배열이라 ::text 통짜 비교로 원소 안 부분일치도 잡는다. recipe_json->>'name' 은
        추출된 요리 이름. :like 는 likeParam 이 %,_,\ 를 리터럴로 escape 해 바인딩(ESCAPE '\' 와 짝).
        searchMine·searchOthers 두 조회의 단일 원본 — 매칭 규칙이 갈리지 않게 한 곳에 둔다. */
    private static final String SEARCH_MATCH =
            "(v.title ILIKE :like ESCAPE '\\' "
            + "OR v.recipe_json->>'name' ILIKE :like ESCAPE '\\' "
            + "OR v.tags::text ILIKE :like ESCAPE '\\')";

    /** 보관함 검색 — 내 등록 중 매칭 (2026-07-16 5차). 상태 무관: 방금 등록해 분석 전인 항목도
        제목으로 찾히게 한다 (내 것이니 진행 상태 그대로 보여주면 됨). 최신 등록이 앞. */
    public List<Row> searchMine(long userId, String query) {
        return jdbc.sql("SELECT " + JOINED_COLUMNS + " " + JOIN
                        + "WHERE r.user_id = :userId AND " + SEARCH_MATCH + " "
                        + "ORDER BY r.registered_at DESC")
                .param("userId", userId).param("like", likeParam(query))
                .query(this::mapRow).list();
    }

    /** 보관함 검색 보완 — 내가 등록 안 한 gikka 전체 완료(DONE) 영상 중 매칭 (전 분류, 2026-07-16 5차).
        "이런 것도 있어요". registeredAt 은 LEFT JOIN 미매칭이라 NULL 로 내려간다(내 등록이 아님).
        video LEFT JOIN 내 registration 패턴 — 추천 3번(내 것 표시 + 전체 풀)도 같은 형태를 재사용한다.
        최신 분석이 앞, limit(표시 개수 원본은 프론트 상수). */
    public List<Row> searchOthers(long userId, String query, int limit) {
        return jdbc.sql("SELECT " + JOINED_COLUMNS
                        + " FROM video v LEFT JOIN registration r"
                        + " ON r.video_id = v.video_id AND r.user_id = :userId"
                        + " WHERE r.user_id IS NULL AND v.status = 'DONE' AND " + SEARCH_MATCH
                        + " ORDER BY v.queued_at DESC LIMIT :limit")
                .param("userId", userId).param("like", likeParam(query)).param("limit", limit)
                .query(this::mapRow).list();
    }

    /** ILIKE 파라미터로 감싸기 — 사용자 입력의 \,%,_ 를 리터럴로 escape (ESCAPE '\' 와 짝).
        안 하면 사용자가 친 %·_ 가 와일드카드로 동작해 엉뚱한 결과가 난다. */
    private static String likeParam(String query) {
        String escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    /** 모니터링 화면 한 행 — 전 사용자 대기열 실시간 추적용 (오너 전용) */
    public record MonitorRow(long userId, String email, String videoId, String url, String title,
                             String category, String status, Integer durationSeconds, int attemptCount,
                             String lastError, Integer analysisSeconds,
                             OffsetDateTime registeredAt, OffsetDateTime analyzingStartedAt,
                             int geminiRetryCount) {
    }

    /** 전 사용자 대기열 — 최신 등록이 앞. limit 필수(표시 개수 원본은 프론트 상수 하나뿐).
        query(검색어)·status 는 선택 필터 (2026-07-18 — 최신 100개 밖의 영상은 화면에 존재
        자체가 안 해 탐색 불가하던 문제. 검색 매칭 규칙은 보관함 검색과 같은 SEARCH_MATCH 재사용). */
    public List<MonitorRow> listForMonitor(int limit, String query, String status) {
        StringBuilder sql = new StringBuilder("""
                        SELECT r.user_id, u.email, v.video_id, v.url, v.title, v.category, v.status,
                               v.duration_seconds, v.attempt_count, v.last_error, v.analysis_seconds,
                               r.registered_at, v.analyzing_started_at, v.gemini_retry_count
                        FROM registration r
                            JOIN video v ON v.video_id = r.video_id
                            JOIN gikka_user u ON u.id = r.user_id
                        """);
        boolean hasQuery = query != null && !query.isBlank();
        List<String> conditions = new java.util.ArrayList<>();
        if (hasQuery) {
            conditions.add(SEARCH_MATCH);
        }
        if (status != null) {
            conditions.add("v.status = :status");
        }
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
        sql.append(" ORDER BY r.registered_at DESC LIMIT :limit");
        var spec = jdbc.sql(sql.toString()).param("limit", limit);
        if (hasQuery) {
            spec = spec.param("like", likeParam(query.trim()));
        }
        if (status != null) {
            spec = spec.param("status", status);
        }
        return spec.query((rs, i) -> new MonitorRow(
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

    /** 모니터 상태 칩용 — 전체(LIMIT 무관) 상태별 등록 행 수. "완료만 훑기·실패만 모아보기"의
        칩 개수가 화면에 잘린 100개가 아니라 전체 사실을 말하게 한다 (2026-07-18) */
    public Map<String, Integer> monitorStatusCounts() {
        return jdbc.sql("""
                        SELECT v.status, COUNT(*) AS cnt
                        FROM registration r JOIN video v ON v.video_id = r.video_id
                        GROUP BY v.status
                        """)
                .query((rs, i) -> Map.entry(rs.getString("status"), rs.getInt("cnt")))
                .list().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
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
