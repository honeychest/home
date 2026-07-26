-- 재료 신고 (2026-07-18 확정 — CONTEXT.md "재료 신고(전력 재분석)" 절).
-- 사용자가 분석 결과의 재료 하나를 "이상하다"고 신고하면, 서로 다른 신고자 수가 임계값
-- (gikka.media.report-analyze-threshold, 지금 1)에 닿을 때 그 영상을 신고 힌트와 함께 기존
-- 재분석 대기열로 보낸다(전력 분석 = Gemini 직행). 지금은 오너에게만 노출되는 일반 사용자 기능.
--
-- ingredient_report: 행 하나 = (영상, 재료, 사용자). UNIQUE 가 1인 1신고를 DB 차원에서 강제 —
--   같은 사람이 계속 눌러 임계값을 뚫는 노이즈를 UI 가 아니라 스키마가 막는다(사용자 확정).
--   status: OPEN(접수) → QUEUED(재분석 대기열로 승격됨) → DONE(재분석 종료).
--   재분석 후에도 이상하면 같은 사용자가 자기 행을 DONE → OPEN 으로 되돌릴 수 있다(여전히 1인 1표).
-- ingredient_report_run: (영상, 재료)당 재분석 실행 기록 + 전후 재료 목록(관찰 데이터 — 이걸 보고
--   다음 단계를 결정하기로 함). 행 수가 곧 실행 횟수라 상한(report-max-runs, 지금 2) 판정에 쓰인다
--   — 같은 건으로 무한 재분석(Gemini 낭비)을 막는 장치. new_ingredients 가 NULL 로 남으면
--   재분석이 FAILED 로 끝난 것(추가 컬럼 없이 자연 표현).
-- video.report_ingredient: 워커에게 "이 분석은 신고 재점검"임을 알리는 힌트(신고된 재료 이름).
--   승격 시 채우고 분석 종료(DONE·FAILED 확정) 시 비운다 — 값이 있으면 Hybrid 가 Gemini 직행.
CREATE TABLE ingredient_report (
    id              BIGSERIAL PRIMARY KEY,
    video_id        TEXT NOT NULL REFERENCES video (video_id),
    ingredient_name TEXT NOT NULL,
    user_id         BIGINT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'OPEN',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at    TIMESTAMPTZ,
    UNIQUE (video_id, ingredient_name, user_id)
);

CREATE INDEX ix_ingredient_report_open ON ingredient_report (video_id, ingredient_name)
    WHERE status = 'OPEN';

CREATE TABLE ingredient_report_run (
    id              BIGSERIAL PRIMARY KEY,
    video_id        TEXT NOT NULL REFERENCES video (video_id),
    ingredient_name TEXT NOT NULL,
    old_ingredients JSONB,
    new_ingredients JSONB,
    ran_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_ingredient_report_run_case ON ingredient_report_run (video_id, ingredient_name);

ALTER TABLE video ADD COLUMN report_ingredient TEXT;
