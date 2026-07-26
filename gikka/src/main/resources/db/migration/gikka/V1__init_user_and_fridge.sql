-- [AGENT] gikka(recipe) DB 초기 스키마 — docs/recipe/CONTEXT.md 확정 결정 기준
-- 다중 사용자 기본 설계 (2026-07-10): 모든 데이터는 사용자 소속. userId 없는 행 금지.
-- 냉장고 데이터 모양은 프론트 fridgeTypes.ts (미래 API 응답)와 1:1 대응해야 한다.

-- 사용자: Google 계정 ID(sub)가 고유 열쇠 (2단계 계정 동기화·중앙 DB의 기반)
CREATE TABLE gikka_user (
    id          BIGSERIAL PRIMARY KEY,
    google_sub  TEXT        NOT NULL UNIQUE,
    email       TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 냉장고 재료: 있다/없다만 관리 (수량·유통기한 없음 — 확정 결정)
CREATE TABLE fridge_item (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES gikka_user (id),
    name        TEXT        NOT NULL,
    added_date  DATE        NOT NULL,           -- 재등록 시 오늘로 갱신 (리필 대응)
    expiring    BOOLEAN     NOT NULL DEFAULT FALSE, -- 임박: 사용자 수동 토글
    UNIQUE (user_id, name)                      -- 같은 재료는 사용자당 1행
);

-- 재료별 추가 횟수 + 숨김: "자주 사는 재료" 버튼의 근거 (숨김 = 편집 모드에서 제거)
CREATE TABLE ingredient_stat (
    user_id     BIGINT  NOT NULL REFERENCES gikka_user (id),
    name        TEXT    NOT NULL,
    add_count   INT     NOT NULL DEFAULT 0,
    hidden      BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (user_id, name)
);

CREATE INDEX idx_fridge_item_user ON fridge_item (user_id);
