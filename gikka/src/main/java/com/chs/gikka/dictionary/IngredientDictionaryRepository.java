// [AGENT] 재료 사전 저장소 (2026-07-17 5차-4 슬라이스1) — 재료 이름별 "양념 여부" 판정의
// 단일 원본(V11 ingredient_dictionary). 이전엔 RecommendRules 코드 상수뿐이라 그 외 양념이 전부
// 주재료로 잡혀 추천 매칭이 거의 안 됐다. RecommendController 가 seasoningNames() 를 읽어
// RecommendRules.classify() 에 넘긴다(순수 모듈 유지 — 저장소를 규칙에 주입하지 않는다).
// 워커가 추출한 새 재료 이름을 upsertPending 으로 이어서 채우고, 오너가 monitor 에서 재분류한다.
// 양념 여부의 단일 원본은 status 하나다 — CONFIRMED_SEASONING 만 양념, 그 외는 주재료(안전 기본값).
// 별도 tier 컬럼을 두지 않는다(status 의 순수 파생이라 컬럼이면 세 write 경로가 동기화를 짊어짐 —
// 2026-07-17 아키텍처 점검에서 제거). gikka 전용 JdbcClient 만 사용 (분리 규율 2·8).
//
// 2026-07-25 `dictionary` 패키지로 이관 — 사전은 registration(분석 파이프라인)의 산출물이자
// recommend(매칭)의 입력이라 어느 한쪽 소유가 아니다. 예전엔 registration 안에 있어서
// recommend 가 registration 을 import 하는 모양이 됐다("추천이 등록에 의존"처럼 보임).
// 이 패키지는 registration 을 import 하지 않는다 — 의존은 한 방향뿐이어야 순환이 안 생긴다.
package com.chs.gikka.dictionary;

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

    /* ── 매칭이 읽는 값들 (RecommendRules.Dictionary 로 조립돼 넘어간다) ──
       세 조회 모두 "멤버의 성격은 대표가 정한다"(V13)를 자기참조 조인으로 표현한다. 안 묶인 행은
       match_key = 자기 이름이라 자기 자신과 조인돼 슬라이스1과 동작이 같다(그룹을 안 쓰면 무변화).
       멤버 자신의 status 는 이 조인에 안 쓰인다 — 대표가 단일 원본이라 멤버 status 는 무시된다. */

    /** 양념 이름 집합 — 대표가 CONFIRMED_SEASONING 인 이름 전부(멤버 포함). 그 외는 주재료 취급. */
    public Set<String> seasoningNames() {
        return namesWhoseRepresentativeIs(STATUS_CONFIRMED_SEASONING);
    }

    /** 기본양념 이름 집합 — 있다고 간주해 부족분에서 아예 뺀다(RecommendRules 참고). */
    public Set<String> basicNames() {
        return namesWhoseRepresentativeIs(STATUS_CONFIRMED_BASIC);
    }

    private Set<String> namesWhoseRepresentativeIs(String status) {
        return jdbc.sql("""
                        SELECT d.name
                        FROM ingredient_dictionary d
                        JOIN ingredient_dictionary rep ON rep.name = d.match_key
                        WHERE rep.status = :status
                        """)
                .param("status", status).query(String.class).set();
    }

    /** 이름 → 매칭 키 전체 (2026-07-17 슬라이스2). 매칭이 이름 대신 이 키를 비교한다. */
    public Map<String, String> matchKeys() {
        return jdbc.sql("SELECT name, match_key FROM ingredient_dictionary")
                .query((rs, i) -> Map.entry(rs.getString("name"), rs.getString("match_key")))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
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

    /** 냉장고 자동완성용 대표 이름 목록 (2026-07-19, 이름순 — 로그인 사용자 공용).
        멤버(변형·오타)는 제외 — "묶여진 단어 기준" 확정. SKIPPED(보류=재료 아님/못 정함) 대표도
        제외한다(튀김 반죽을 추천 검색어로 내밀면 안 됨). */
    public List<String> representativeNames() {
        return jdbc.sql("""
                        SELECT name FROM ingredient_dictionary
                        WHERE name = match_key AND status <> 'SKIPPED' ORDER BY name
                        """)
                .query(String.class).list();
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
     * 파이프라인 자동 병합 (2026-07-18 확정 — 수량·단위 변형 규칙과 AI 자동 판정이 공용).
     * 오너·과거 판정을 절대 덮지 않도록 아직 판정 전(PENDING)·미병합(match_key=자기 이름)인 행만,
     * 대표가 사전에 실재할 때만 묶는다. 대표가 이미 남의 멤버면 그 대표의 대표를 따라간다
     * (깊이 1 평탄화 — merge 와 같은 규칙을 SQL 조인 한 번으로).
     *
     * @return 실제로 묶였으면 최종 match_key(평탄화 반영 — 변경 로그가 정확한 값을 남기게), 아니면 empty
     */
    public java.util.Optional<String> autoMergeVariant(String name, String representative) {
        if (name.equals(representative)) {
            return java.util.Optional.empty();
        }
        return jdbc.sql("""
                        UPDATE ingredient_dictionary d
                        SET match_key = rep.match_key, updated_at = now()
                        FROM ingredient_dictionary rep
                        WHERE d.name = :name AND rep.name = :rep
                          AND d.status = 'PENDING' AND d.match_key = d.name
                        RETURNING d.match_key
                        """)
                .param("name", name).param("rep", representative)
                .query(String.class).optional();
    }

    /**
     * 파이프라인 자동 분류 (2026-07-18) — 아직 판정 전(PENDING)인 것만 status 를 정한다.
     * confirmSeasoningIfPending 과 같은 "사람 판정 우선" 가드의 일반형 — AI 자동 판정이
     * SEASONING/BASIC 을 적용할 때 쓴다 (MAIN 은 PENDING 과 동작이 같아 적용할 이유가 없음).
     * @return 실제로 바뀌었는가 (변경 로그 기록 여부 판정용)
     */
    public boolean updateStatusIfPending(String name, String status) {
        return jdbc.sql("""
                        UPDATE ingredient_dictionary
                        SET status = :status, updated_at = now()
                        WHERE name = :name AND status = 'PENDING'
                        """)
                .param("status", status).param("name", name).update() > 0;
    }

    /**
     * 오너의 그룹 확정 (2026-07-17 슬라이스2) — name 을 matchKey 그룹에 넣는다.
     * name.equals(matchKey) 면 그룹 해제(자기 이름으로 되돌림).
     *
     * <p>묶기는 오직 오너 확정만 한다 — LLM 은 제안까지만이고 자동 병합은 없다(안전 비대칭 규칙,
     * CONTEXT.md). 쪼개기(기본값 = 자기 이름)는 틀려도 "덜 매칭"으로 끝나지만, 묶기는 틀리면
     * 없는 재료를 있다고 하게 되기 때문이다.
     *
     * <p>그룹 깊이는 항상 1로 평탄화한다 — A→B 인데 B→C 면 A 의 키(B)와 C 의 키(C)가 달라져
     * 매칭이 조용히 깨진다. 그래서 (1) 대표의 대표를 따라가고, (2) 지금 name 을 대표로 삼고 있던
     * 멤버들도 같은 대표로 함께 옮긴다. 이 평탄화 덕분에 순환(A→B 뒤 B→A)도 자연히 안 생긴다 —
     * 그 경우 대표를 따라가면 자기 자신이라 그룹 해제로 귀결된다.
     *
     * <p>멤버의 status 는 건드리지 않는다 — 묶여 있는 동안엔 무시되고(대표가 정함), 나중에 그룹을
     * 풀면 원래 판정이 그대로 돌아온다(오너의 이전 작업을 안 지운다).
     *
     * @return false = 없는 이름 또는 사전에 없는 대표
     */
    public boolean merge(String name, String matchKey) {
        if (name.equals(matchKey)) {
            return jdbc.sql("""
                            UPDATE ingredient_dictionary SET match_key = name, updated_at = now()
                            WHERE name = :name
                            """)
                    .param("name", name).update() > 0;
        }
        String representative = jdbc.sql("SELECT match_key FROM ingredient_dictionary WHERE name = :name")
                .param("name", matchKey).query(String.class).optional().orElse(null);
        if (representative == null) {
            return false; // 사전에 없는 대표 — FK 가 막기 전에 여기서 거른다(400/404 를 주기 위해)
        }
        return jdbc.sql("""
                        UPDATE ingredient_dictionary SET match_key = :rep, updated_at = now()
                        WHERE name = :name OR match_key = :name
                        """)
                .param("rep", representative).param("name", name).update() > 0;
    }

    /** 오너의 일괄 그룹 확정 — [AI 점검] 병합 제안 전체 적용용. updateStatuses 와 같은 이유로
        없는 이름·없는 대표는 조용히 건너뛴다(한 건 때문에 전체를 실패시키지 않는다).
        @return 실제로 바뀐 건수 */
    public int mergeAll(Map<String, String> nameToMatchKey) {
        int changed = 0;
        for (Map.Entry<String, String> d : nameToMatchKey.entrySet()) {
            changed += merge(d.getKey(), d.getValue()) ? 1 : 0;
        }
        return changed;
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
