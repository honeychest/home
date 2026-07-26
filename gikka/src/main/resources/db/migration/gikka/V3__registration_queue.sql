-- [AGENT] 3차: 영상 등록·분석 대기열 (CONTEXT.md 파이프라인 — 메타 조회 → 7분 컷 → 분류+추출)
-- 대기열은 DB 테이블 + 단일 워커 (별도 큐 인프라 없음 — 확정 결정).
-- recipe_json: 추출 결과(재료 원문 그대로) — 재료 정규화 테이블은 4차(재료 사전)에서 추가·소급.

CREATE TABLE registration (
    user_id             BIGINT      NOT NULL REFERENCES gikka_user (id),
    video_id            TEXT        NOT NULL,
    url                 TEXT        NOT NULL,
    platform            TEXT        NOT NULL DEFAULT 'YOUTUBE',       -- 확장성 선반영 (6차: TIKTOK, REELS)
    category            TEXT        NULL,                              -- RECIPE/TIP/ETC — 분석 전 NULL
    status              TEXT        NOT NULL,                          -- WAITING/ANALYZING/DONE/TOO_LONG/FAILED
    title               TEXT        NULL,                              -- 메타 조회 결과 (실패 시 NULL)
    thumbnail_url       TEXT        NULL,
    duration_seconds    INT         NULL,
    recipe_json         JSONB       NULL,                              -- ExtractedRecipe (DONE + RECIPE 일 때만)
    attempt_count       INT         NOT NULL DEFAULT 0,                -- 3회 실패 → FAILED
    analyzing_started_at TIMESTAMPTZ NULL,                             -- 워커 사망 대비 stale 회수 기준
    registered_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, video_id)                                    -- 중복 등록 방지 (고유 열쇠 = 영상 ID)
);

CREATE INDEX idx_registration_status ON registration (status);

-- Gemini 호출 속도 조절 단일 행: 앱 2인스턴스가 합산으로 분당 한도를 넘지 않게 (원자적 UPDATE 로 슬롯 획득)
CREATE TABLE gemini_rate (
    id           INT         PRIMARY KEY,
    last_call_at TIMESTAMPTZ NOT NULL
);
INSERT INTO gemini_rate (id, last_call_at) VALUES (1, now() - interval '1 hour');
