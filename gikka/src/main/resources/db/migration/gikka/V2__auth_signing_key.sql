-- [AGENT] JWT 서명 키 보관 — 서버 .env 추가 없이 첫 기동 시 자동 생성, 2인스턴스가 공유
CREATE TABLE auth_signing_key (
    id         INT         PRIMARY KEY CHECK (id = 1),  -- 항상 1행만
    secret     TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
