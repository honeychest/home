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
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IngredientDictionaryRepository {

    /** status 값 — 재료 성격의 단일 원본. 판정 전(PENDING·SKIPPED)은 전부 주재료 취급(안전 기본값 —
        양념으로 잘못 빼면 레시피가 추천에서 사라지지만, 주재료로 두면 "부족 재료"로 보일 뿐). */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SKIPPED = "SKIPPED";
    public static final String STATUS_CONFIRMED_MAIN = "CONFIRMED_MAIN";
    /** 없을 수 있는 양념(고추장·굴소스…) — 부족하면 "양념만 부족" 섹션으로 살아남는다 */
    public static final String STATUS_CONFIRMED_SEASONING = "CONFIRMED_SEASONING";
    /** 집에 늘 있는 상비 양념(물·소금·설탕…) — 매칭에서 아예 건너뛴다(V12).
        이게 없으면 "완전 가능" 섹션이 구조적으로 항상 0이 된다(레시피 115개 중 48개가 "물"을
        재료로 적는데 냉장고에 물을 넣는 사람은 없음 — 2026-07-17 실측). */
    public static final String STATUS_CONFIRMED_BASIC = "CONFIRMED_BASIC";

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

    /** 매칭이 읽는 기본양념 이름 집합 — 있다고 간주해 부족분에서 아예 뺀다(RecommendRules 참고). */
    public Set<String> basicNames() {
        return jdbc.sql("SELECT name FROM ingredient_dictionary WHERE status = 'CONFIRMED_BASIC'")
                .query(String.class).set();
    }

    /** 오너의 일괄 판정 (이름 → status) — [AI 점검] 제안 전체 적용용. 제안이 83개라 한 건씩
        왕복하면 못 쓴다(2026-07-17 실측). 없는 이름은 조용히 건너뛴다 — 일괄이라 한 건 때문에
        전체를 실패시키지 않는다(못 바꾼 건 다음 점검에 다시 제안된다).
        @return 실제로 바뀐 건수 */
    public int updateStatuses(Map<String, String> nameToStatus) {
        int changed = 0;
        for (Map.Entry<String, String> d : nameToStatus.entrySet()) {
            changed += updateStatus(d.getKey(), d.getValue()) ? 1 : 0;
        }
        return changed;
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
