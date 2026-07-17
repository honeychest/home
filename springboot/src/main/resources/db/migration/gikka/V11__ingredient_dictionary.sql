-- 재료 사전 (2026-07-17 5차-4 슬라이스1, CONTEXT.md "5차-4 사전·매칭 재설계") — 재료 이름별
-- 양념 여부 판정의 단일 원본. 이전엔 RecommendRules 코드 상수(SEASONINGS 19개)뿐이라 그 외 양념이
-- 전부 주재료로 잡혀 추천 매칭이 거의 안 됐다. classify() 가 이 테이블을 조회한다.
--   status    : 양념 여부의 단일 원본 — PENDING(신규 자동) / SKIPPED(오너 보류) /
--               CONFIRMED_MAIN(주재료 확정) / CONFIRMED_SEASONING(양념 확정).
--               매칭이 읽는 "양념 이름 집합" = WHERE status='CONFIRMED_SEASONING'. 그 외(판정 전
--               PENDING·SKIPPED·CONFIRMED_MAIN)는 전부 주재료 취급(안전 기본값 — 양념으로 잘못
--               빼는 것보다 "부족 재료"가 덜 위험). 별도 tier 컬럼을 두지 않는다 — status 의 순수
--               파생이라 컬럼으로 두면 세 write 경로가 동기화를 짊어진다(2026-07-17 아키텍처 점검).
--   match_key : 슬라이스2(그룹 매칭)용 — 이번엔 전부 자기 이름이라 매칭에 영향 없음(기본=엄격).
CREATE TABLE ingredient_dictionary (
    name        text PRIMARY KEY,
    match_key   text        NOT NULL,
    status      text        NOT NULL DEFAULT 'PENDING',
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

-- 기존 코드 상수(RecommendRules.SEASONINGS)를 CONFIRMED_SEASONING 으로 이관.
-- 구 ALIASES("다진 마늘"="다진마늘", "백식초"="식초")는 양념 목적상 양쪽 표기를 모두 양념으로
-- 시드하면 끝 — 슬라이스1은 이름 정확 매칭이라 별칭 병합이 불필요하다(그룹 병합은 슬라이스2).
INSERT INTO ingredient_dictionary (name, match_key, status) VALUES
    ('물',       '물',       'CONFIRMED_SEASONING'),
    ('소금',     '소금',     'CONFIRMED_SEASONING'),
    ('설탕',     '설탕',     'CONFIRMED_SEASONING'),
    ('간장',     '간장',     'CONFIRMED_SEASONING'),
    ('고추장',   '고추장',   'CONFIRMED_SEASONING'),
    ('고춧가루', '고춧가루', 'CONFIRMED_SEASONING'),
    ('된장',     '된장',     'CONFIRMED_SEASONING'),
    ('후추',     '후추',     'CONFIRMED_SEASONING'),
    ('식용유',   '식용유',   'CONFIRMED_SEASONING'),
    ('참기름',   '참기름',   'CONFIRMED_SEASONING'),
    ('들기름',   '들기름',   'CONFIRMED_SEASONING'),
    ('다진마늘', '다진마늘', 'CONFIRMED_SEASONING'),
    ('다진생강', '다진생강', 'CONFIRMED_SEASONING'),
    ('맛술',     '맛술',     'CONFIRMED_SEASONING'),
    ('식초',     '식초',     'CONFIRMED_SEASONING'),
    ('물엿',     '물엿',     'CONFIRMED_SEASONING'),
    ('올리고당', '올리고당', 'CONFIRMED_SEASONING'),
    ('전분가루', '전분가루', 'CONFIRMED_SEASONING'),
    ('통깨',     '통깨',     'CONFIRMED_SEASONING'),
    ('다진 마늘', '다진 마늘', 'CONFIRMED_SEASONING'),
    ('백식초',    '백식초',    'CONFIRMED_SEASONING');

-- 이미 추출된 요리 재료 이름을 사전에 채운다(PENDING) — 오너가 monitor 에서 검토·재분류할 수
-- 있도록. 매칭 동작은 PENDING=주재료라 현행과 동일(안전). 새 영상 이름은 워커가 이어서 채운다.
INSERT INTO ingredient_dictionary (name, match_key, status)
SELECT DISTINCT btrim(elem.value), btrim(elem.value), 'PENDING'
FROM video,
     LATERAL jsonb_array_elements_text(recipe_json -> 'ingredients') AS elem(value)
WHERE video.status = 'DONE' AND video.category = 'RECIPE'
  AND recipe_json IS NOT NULL
  AND jsonb_typeof(recipe_json -> 'ingredients') = 'array'  -- 비배열 이상 행이 있어도 마이그레이션이 안 죽게
  AND btrim(elem.value) <> ''
ON CONFLICT (name) DO NOTHING;
