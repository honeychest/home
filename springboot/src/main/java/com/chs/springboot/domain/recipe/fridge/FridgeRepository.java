// [AGENT] 냉장고 모듈 — 저장·동작 규칙을 한 파일에 (2026-07-10 아키텍처 점검: 통과 계층이던
// FridgeService 를 흡수. 관찰 동작의 원본은 프론트 구 localStorage 구현체와 동일 의무)
// gikka DB 전용 JdbcClient·TransactionTemplate 만 사용 (분리 규율 2·8).
package com.chs.springboot.domain.recipe.fridge;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class FridgeRepository {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public FridgeRepository(@Qualifier("gikkaJdbcClient") JdbcClient jdbc,
                            @Qualifier("gikkaTxTemplate") TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    record Row(long id, String name, LocalDate addedDate, boolean expiring) {
        FridgeItemResponse toResponse() {
            return new FridgeItemResponse(String.valueOf(id), name, addedDate.toString(), expiring);
        }
    }

    public List<FridgeItemResponse> list(long userId) {
        return jdbc.sql("SELECT id, name, added_date, expiring FROM fridge_item WHERE user_id = :userId")
                .param("userId", userId)
                .query((rs, i) -> new Row(rs.getLong("id"), rs.getString("name"),
                        rs.getDate("added_date").toLocalDate(), rs.getBoolean("expiring")))
                .list().stream().map(Row::toResponse).toList();
    }

    /** 추가 = 통계 +1 & 숨김 해제 + 재료 upsert(재등록 시 등록일 오늘 갱신) — 한 트랜잭션 */
    public FridgeItemResponse add(long userId, String rawName) {
        String name = rawName.trim();
        return tx.execute(status -> {
            incrementStatAndUnhide(userId, name);
            return upsertItem(userId, name, LocalDate.now());
        });
    }

    /** @return false = 남의 데이터거나 없는 id (컨트롤러에서 404) */
    public boolean remove(long userId, long itemId) {
        return jdbc.sql("DELETE FROM fridge_item WHERE id = :id AND user_id = :userId")
                .param("id", itemId).param("userId", userId).update() > 0;
    }

    /** name / addedDate / expiring 중 넘어온 것만 갱신 (COALESCE). @return false = 없는 id */
    public boolean update(long userId, long itemId,
                          Optional<String> name, Optional<LocalDate> addedDate, Optional<Boolean> expiring) {
        return jdbc.sql("""
                        UPDATE fridge_item SET
                            name = COALESCE(:name, name),
                            added_date = COALESCE(:addedDate, added_date),
                            expiring = COALESCE(:expiring, expiring)
                        WHERE id = :id AND user_id = :userId
                        """)
                .param("name", name.map(String::trim).orElse(null))
                .param("addedDate", addedDate.orElse(null))
                .param("expiring", expiring.orElse(null))
                .param("id", itemId).param("userId", userId)
                .update() > 0;
    }

    /** 자주 사는 재료 상위 N — 추가 횟수 순, 시드 병합, 숨김 제외 */
    public List<String> frequentIngredients(long userId, int limit) {
        List<Stat> stats = jdbc
                .sql("SELECT name, add_count, hidden FROM ingredient_stat WHERE user_id = :userId")
                .param("userId", userId)
                .query((rs, i) -> new Stat(rs.getString("name"), rs.getInt("add_count"), rs.getBoolean("hidden")))
                .list();
        return rankFrequent(stats, SeedIngredients.LIST, limit);
    }

    /** 편집 모드 제거: 숨김 + 횟수 0 리셋 (재추가 시 1부터 — 구 localStorage 구현과 동일 동작) */
    public void removeFrequentIngredient(long userId, String rawName) {
        jdbc.sql("""
                        INSERT INTO ingredient_stat (user_id, name, add_count, hidden)
                        VALUES (:userId, :name, 0, TRUE)
                        ON CONFLICT (user_id, name) DO UPDATE SET hidden = TRUE, add_count = 0
                        """)
                .param("userId", userId).param("name", rawName.trim()).update();
    }

    public record Stat(String name, int addCount, boolean hidden) {
    }

    /** 순위 규칙 (순수 함수 — DB 없이 테스트): 시드는 횟수 0으로 병합, 숨김은 시드여도 제외 */
    static List<String> rankFrequent(List<Stat> stats, List<String> seeds, int limit) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Set<String> hidden = new HashSet<>();
        for (Stat stat : stats) {
            if (stat.hidden()) {
                hidden.add(stat.name());
            } else {
                counts.put(stat.name(), stat.addCount());
            }
        }
        for (String seed : seeds) {
            if (!hidden.contains(seed)) {
                counts.putIfAbsent(seed, 0);
            }
        }
        return counts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    private void incrementStatAndUnhide(long userId, String name) {
        jdbc.sql("""
                        INSERT INTO ingredient_stat (user_id, name, add_count, hidden)
                        VALUES (:userId, :name, 1, FALSE)
                        ON CONFLICT (user_id, name)
                        DO UPDATE SET add_count = ingredient_stat.add_count + 1, hidden = FALSE
                        """)
                .param("userId", userId).param("name", name).update();
    }

    private FridgeItemResponse upsertItem(long userId, String name, LocalDate today) {
        return jdbc.sql("""
                        INSERT INTO fridge_item (user_id, name, added_date)
                        VALUES (:userId, :name, :today)
                        ON CONFLICT (user_id, name) DO UPDATE SET added_date = :today
                        RETURNING id, name, added_date, expiring
                        """)
                .param("userId", userId).param("name", name).param("today", today)
                .query((rs, i) -> new Row(rs.getLong("id"), rs.getString("name"),
                        rs.getDate("added_date").toLocalDate(), rs.getBoolean("expiring")))
                .single().toResponse();
    }
}
