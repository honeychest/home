// [AGENT] 재료 사전 저장소 (2026-07-17 5차-4 슬라이스1) — 재료 이름별 "양념 여부" 판정의
// 단일 원본(V11 ingredient_dictionary). 이전엔 RecommendRules 코드 상수뿐이라 그 외 양념이 전부
// 주재료로 잡혀 추천 매칭이 거의 안 됐다. RecommendController 가 seasoningNames() 를 읽어
// RecommendRules.classify() 에 넘긴다(순수 모듈 유지 — 저장소를 규칙에 주입하지 않는다).
// 워커가 추출한 새 재료 이름을 upsertPending 으로 이어서 채우고, 오너가 monitor 에서 재분류한다.
// 양념 여부의 단일 원본은 status 하나다 — CONFIRMED_SEASONING 만 양념, 그 외는 주재료(안전 기본값).
// 별도 tier 컬럼을 두지 않는다(status 의 순수 파생이라 컬럼이면 세 write 경로가 동기화를 짊어짐 —
// 2026-07-17 아키텍처 점검에서 제거). gikka 전용 JdbcClient 만 사용 (분리 규율 2·8).
package com.chs.springboot.domain.recipe.registration;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IngredientDictionaryRepository {

    /** status 값 — 양념 여부의 단일 원본(CONFIRMED_SEASONING 만 양념, 그 외는 주재료 안전 기본값) */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SKIPPED = "SKIPPED";
    public static final String STATUS_CONFIRMED_MAIN = "CONFIRMED_MAIN";
    public static final String STATUS_CONFIRMED_SEASONING = "CONFIRMED_SEASONING";

    private final JdbcClient jdbc;

    public IngredientDictionaryRepository(@Qualifier("gikkaJdbcClient") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Entry(String name, String matchKey, String status) {
    }

    /** 매칭이 읽는 양념 이름 집합 — RecommendRules.classify() 에 넘긴다(그 외 이름은 주재료 취급). */
    public Set<String> seasoningNames() {
        return jdbc.sql("SELECT name FROM ingredient_dictionary WHERE status = 'CONFIRMED_SEASONING'")
                .query(String.class).set();
    }

    /** 오너 사전 관리 화면용 전체 목록 (이름순). */
    public List<Entry> all() {
        return jdbc.sql("SELECT name, match_key, status FROM ingredient_dictionary ORDER BY name")
                .query((rs, i) -> new Entry(
                        rs.getString("name"), rs.getString("match_key"), rs.getString("status")))
                .list();
    }

    /** 추출된 재료 이름을 사전에 신규 등록(PENDING). 이미 있으면 그대로 둔다 — 워커가 매 RECIPE 분석 뒤 호출.
        match_key 기본값 = 자기 이름(슬라이스1은 그룹 병합 없음). */
    public void upsertPending(Collection<String> names) {
        Set<String> cleaned = names.stream()
                .filter(n -> n != null && !n.isBlank())
                .map(String::trim)
                .collect(Collectors.toSet());
        for (String name : cleaned) {
            jdbc.sql("""
                            INSERT INTO ingredient_dictionary (name, match_key, status)
                            VALUES (:name, :name, 'PENDING')
                            ON CONFLICT (name) DO NOTHING
                            """)
                    .param("name", name).update();
        }
    }

    /**
     * LLM 이 "확신 있는 양념"으로 판정한 이름을 자동 확정(CONFIRMED_SEASONING) — 단 아직 판정 전
     * (PENDING)인 것만. 오너가 이미 정한 것(CONFIRMED_*·SKIPPED)은 절대 덮어쓰지 않는다(사람 판정
     * 우선 — 5차-4 슬라이스1-C, 안전 비대칭 원칙). 워커가 매 RECIPE 분석 뒤 upsertPending 다음에 호출.
     */
    public void confirmSeasoningIfPending(Collection<String> names) {
        if (names == null) {
            return;
        }
        Set<String> cleaned = names.stream()
                .filter(n -> n != null && !n.isBlank())
                .map(String::trim)
                .collect(Collectors.toSet());
        for (String name : cleaned) {
            jdbc.sql("""
                            UPDATE ingredient_dictionary
                            SET status = 'CONFIRMED_SEASONING', updated_at = now()
                            WHERE name = :name AND status = 'PENDING'
                            """)
                    .param("name", name).update();
        }
    }

    /**
     * 오너 판정 — status 를 정한다(양념 여부는 status 가 곧 원본이라 파생 필드 없음).
     * @return false = 없는 이름 (404)
     */
    public boolean updateStatus(String name, String status) {
        return jdbc.sql("""
                        UPDATE ingredient_dictionary
                        SET status = :status, updated_at = now()
                        WHERE name = :name
                        """)
                .param("status", status).param("name", name)
                .update() > 0;
    }
}
