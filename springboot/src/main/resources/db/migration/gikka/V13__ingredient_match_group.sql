-- 재료 그룹 매칭 (2026-07-17 5차-4 슬라이스2, CONTEXT.md "5차-4 사전·매칭 재설계") — match_key 는
-- V11 에 컬럼만 만들어두고 아무도 안 썼다(전부 자기 이름, 읽는 코드도 쓰는 API 도 없었음).
-- 이제 매칭이 이름 정확 일치 대신 match_key 비교를 쓴다: "쌀떡≠밀떡, 신라면≠사각라면" 처럼
-- 대체 가능한 스테이플이 안 잡혀 추천이 막히던 문제(실측: 위로 올라오는 레시피 8개 → 33개).
--
-- 그룹의 뜻: match_key = "무엇이 있으면 이걸 가진 걸로 칠까". 기본값 = 자기 이름(안 묶으면 현행
-- 엄격 매칭 그대로 — 안전 기본값). 묶인 멤버(match_key <> name)의 양념 여부는 **대표를 따른다**
-- (자기 status 는 무시됨) — 양념 여부의 단일 원본은 여전히 status 하나이고, 멤버 status 를 따로
-- 관리하면 "대표는 양념인데 멤버는 주재료"인 모순을 사람이 손으로 막아야 한다(2026-07-17 확정).
--
-- FK(자기참조): 대표는 반드시 사전에 있는 이름이어야 한다. 없는 이름을 가리키면 조회 조인이
-- 실패해 그 재료가 조용히 주재료로 떨어지는데, 그걸 코드가 아니라 DB 가 막게 한다.
-- 그룹 깊이는 항상 1이다(A→B→C 금지 — A 와 C 의 키가 서로 달라져 매칭이 깨진다). 이건 FK 로는
-- 못 막아 IngredientDictionaryRepository.merge() 가 대표의 대표를 따라가 평탄화한다.
CREATE INDEX ix_ingredient_dictionary_match_key ON ingredient_dictionary (match_key);

ALTER TABLE ingredient_dictionary
    ADD CONSTRAINT fk_ingredient_dictionary_match_key
    FOREIGN KEY (match_key) REFERENCES ingredient_dictionary (name);
