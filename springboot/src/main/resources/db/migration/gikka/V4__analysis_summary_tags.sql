-- [AGENT] 요약·검색 태그 저장 (2026-07-13 확정 — CONTEXT.md 분석 파이프라인 3번)
-- summary: TIP/ETC 요점 요약 2~3문장 (RECIPE 는 name·steps 가 그 역할 — NULL).
-- tags: 검색용 키워드 (전 분류 공통, JSONB 문자열 배열) — 검색 기능 자체는 나중, 지금은 적립만.
-- summary_version: 요약·태그의 모델·프롬프트 세대 (구버전만 골라 재생성 — 확장성 선반영).
--   RECIPE 는 recipe_json 안의 summaryVersion 이 원본이지만, 분류 무관 일괄 조회를 위해 컬럼에도 기록.

ALTER TABLE registration
    ADD COLUMN summary         TEXT  NULL,
    ADD COLUMN tags            JSONB NULL,
    ADD COLUMN summary_version INT   NULL;
