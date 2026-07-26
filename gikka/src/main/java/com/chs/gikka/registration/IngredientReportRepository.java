// [AGENT] 재료 신고 저장소 (2026-07-18, V15 — CONTEXT.md "재료 신고(전력 재분석)" 절).
// 1인 1신고는 UNIQUE(video, name, user)가 강제하고, 임계값(서로 다른 신고자 수)·실행 상한
// (run 행 수) 판정은 전부 SQL 이 담당한다 — 워커는 여기 결과만 따른다.
// 전후 재료 목록(diff)은 recipe_json->'ingredients' 를 SQL 로 그대로 떠서 남긴다(자바 파싱 없음)
// — "재분석이 실제로 뭘 바꾸는가"의 관찰 데이터(다음 단계 결정의 근거, 사용자 확정).
// gikka 전용 JdbcClient 만 사용 (분리 규율 2·8).
package com.chs.gikka.registration;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IngredientReportRepository {

    private final JdbcClient jdbc;

    public IngredientReportRepository(@Qualifier("gikkaJdbcClient") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 임계값을 넘어 재분석 대기열로 보낼 (영상, 재료) 한 건 */
    public record ReportCase(String videoId, String ingredientName) {
    }

    /**
     * 신고 접수 — 같은 (영상, 재료, 사용자)가 이미 OPEN/QUEUED 면 아무 일도 안 함(1인 1신고).
     * 처리(DONE)가 끝난 뒤의 재신고는 자기 행을 OPEN 으로 되살린다(여전히 1인 1표 — 새 행 아님).
     * @return true = 이번에 접수됨(신규 또는 재접수), false = 이미 접수돼 있음
     */
    public boolean report(String videoId, String ingredientName, long userId) {
        return jdbc.sql("""
                        INSERT INTO ingredient_report (video_id, ingredient_name, user_id)
                        VALUES (:videoId, :name, :userId)
                        ON CONFLICT (video_id, ingredient_name, user_id)
                            DO UPDATE SET status = 'OPEN', created_at = now(), processed_at = NULL
                            WHERE ingredient_report.status = 'DONE'
                        RETURNING id
                        """)
                .param("videoId", videoId).param("name", ingredientName).param("userId", userId)
                .query(Long.class).optional().isPresent();
    }

    /** 이 사용자가 이 영상에서 접수해 둔(아직 처리 안 끝난) 재료 이름들 — 신고 버튼 상태 표시용 */
    public List<String> activeNames(String videoId, long userId) {
        return jdbc.sql("""
                        SELECT ingredient_name FROM ingredient_report
                        WHERE video_id = :videoId AND user_id = :userId AND status IN ('OPEN', 'QUEUED')
                        """)
                .param("videoId", videoId).param("userId", userId)
                .query(String.class).list();
    }

    /**
     * 임계값(서로 다른 신고자 수)을 넘고 실행 상한 미달이며 영상이 지금 대기열에 없는 건 하나를
     * 골라 OPEN → QUEUED 로 원자적으로 전환한다. 2인스턴스 동시 실행이면 한쪽의 UPDATE 가
     * 0행이 돼(이미 QUEUED) 자연히 한 번만 승격된다.
     */
    public Optional<ReportCase> claimEligibleCase(int threshold, int maxRuns) {
        return jdbc.sql("""
                        UPDATE ingredient_report SET status = 'QUEUED'
                        WHERE status = 'OPEN' AND (video_id, ingredient_name) IN (
                            SELECT r.video_id, r.ingredient_name
                            FROM ingredient_report r
                            JOIN video v ON v.video_id = r.video_id
                            WHERE r.status = 'OPEN' AND v.status NOT IN ('WAITING', 'ANALYZING')
                            GROUP BY r.video_id, r.ingredient_name
                            HAVING COUNT(*) >= :threshold
                               AND (SELECT COUNT(*) FROM ingredient_report_run u
                                    WHERE u.video_id = r.video_id
                                      AND u.ingredient_name = r.ingredient_name) < :maxRuns
                            ORDER BY MIN(r.created_at) LIMIT 1
                        )
                        RETURNING video_id, ingredient_name
                        """)
                .param("threshold", threshold).param("maxRuns", maxRuns)
                .query((rs, i) -> new ReportCase(rs.getString("video_id"), rs.getString("ingredient_name")))
                .list().stream().findFirst();
    }

    /** 재분석 실행 기록 시작 — 지금(초기화 전)의 재료 목록을 old 로 뜬다. 큐잉 직전에 호출할 것 */
    public void recordRunStart(String videoId, String ingredientName) {
        jdbc.sql("""
                        INSERT INTO ingredient_report_run (video_id, ingredient_name, old_ingredients)
                        SELECT video_id, :name, recipe_json -> 'ingredients'
                        FROM video WHERE video_id = :videoId
                        """)
                .param("videoId", videoId).param("name", ingredientName).update();
    }

    /**
     * 신고 건 마감 — 분석이 끝난(DONE·FAILED 확정) 뒤 워커가 호출. 최신 실행 기록에 새 재료
     * 목록을 채우고(FAILED 면 recipe_json 이 비어 있어 NULL 로 남음 = 실패의 자연 표현),
     * QUEUED 신고 행을 전부 DONE 으로 돌린다(이후 같은 사용자의 재신고 = OPEN 재접수 가능).
     */
    public void completeCase(String videoId, String ingredientName) {
        jdbc.sql("""
                        UPDATE ingredient_report_run
                        SET new_ingredients = (SELECT recipe_json -> 'ingredients' FROM video
                                               WHERE video_id = :videoId)
                        WHERE id = (SELECT id FROM ingredient_report_run
                                    WHERE video_id = :videoId AND ingredient_name = :name
                                      AND new_ingredients IS NULL
                                    ORDER BY ran_at DESC LIMIT 1)
                        """)
                .param("videoId", videoId).param("name", ingredientName).update();
        jdbc.sql("""
                        UPDATE ingredient_report SET status = 'DONE', processed_at = now()
                        WHERE video_id = :videoId AND ingredient_name = :name AND status = 'QUEUED'
                        """)
                .param("videoId", videoId).param("name", ingredientName).update();
    }
}
