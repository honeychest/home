// [AGENT] 재료 사전 자동 반영 로그 저장소 (2026-07-18, V14) — 파이프라인이 사전을 스스로 바꾼
// 내역의 단일 원본. 오너는 monitor "자동 반영 내역"에서 이 로그를 사후 감사하고, 이상하면 기존
// 그룹 해제·재분류로 복구한다(append 전용 — 수정·삭제 없음). gikka 전용 JdbcClient 만 사용
// (분리 규율 2·8). 오너 수동 조작은 기록하지 않는다(자기가 한 일이라 감사 대상이 아님).
package com.chs.springboot.domain.recipe.dictionary;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IngredientChangeLogRepository {

    public static final String ACTION_CLASSIFY = "CLASSIFY";
    public static final String ACTION_MERGE = "MERGE";
    /** 수량·단위 변형 기계 규칙 (RegistrationRules.representativeCandidate) */
    public static final String SOURCE_AUTO_VARIANT = "AUTO_VARIANT";
    /** 신규 PENDING AI 판정 (IngredientAutoJudge) */
    public static final String SOURCE_AUTO_AUDIT = "AUTO_AUDIT";

    private final JdbcClient jdbc;

    public IngredientChangeLogRepository(@Qualifier("gikkaJdbcClient") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Entry(long id, String name, String action, String oldValue, String newValue,
                        String source, OffsetDateTime createdAt) {
    }

    public void append(String name, String action, String oldValue, String newValue, String source) {
        jdbc.sql("""
                        INSERT INTO ingredient_change_log (name, action, old_value, new_value, source)
                        VALUES (:name, :action, :old, :new, :source)
                        """)
                .param("name", name).param("action", action)
                .param("old", oldValue).param("new", newValue).param("source", source)
                .update();
    }

    /** monitor "자동 반영 내역"용 최신순 목록 */
    public List<Entry> recent(int limit) {
        return jdbc.sql("""
                        SELECT id, name, action, old_value, new_value, source, created_at
                        FROM ingredient_change_log ORDER BY created_at DESC, id DESC LIMIT :limit
                        """)
                .param("limit", limit)
                .query((rs, i) -> new Entry(rs.getLong("id"), rs.getString("name"),
                        rs.getString("action"), rs.getString("old_value"), rs.getString("new_value"),
                        rs.getString("source"), rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }
}
