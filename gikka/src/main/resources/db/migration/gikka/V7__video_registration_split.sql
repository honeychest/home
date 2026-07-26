-- [AGENT] video/registration 분리 (CONTEXT.md "영상 분석의 테이블 분리" 확정 실행, 2026-07-13)
-- video = 분석 결과 원본(video_id 당 1행, 대기열도 이 테이블 기준) — 같은 영상이 여러 사용자에게
-- 등록돼도 분석은 1회만 하도록 구조로 보장하는 게 목적 (2단계 중앙 DB 공유의 로컬 선행 형태).
-- registration = 사용자<->영상 연결만(user_id, video_id, registered_at) — 개인 목록 정렬 근거.
-- queued_at 신설: 대기열 순번 기준. 이전엔 registration.registered_at 이 이 역할을 겸했으나
-- 대기열이 영상 단위로 도니 분리 (최초 등록 시각으로 시작, 재분석 시 now() 로 갱신되어 맨 뒤로 감).

CREATE TABLE video (
    video_id             TEXT        PRIMARY KEY,
    platform             TEXT        NOT NULL DEFAULT 'YOUTUBE',
    url                  TEXT        NOT NULL,
    title                TEXT        NULL,
    thumbnail_url        TEXT        NULL,
    duration_seconds     INT         NULL,
    status               TEXT        NOT NULL,
    category             TEXT        NULL,
    recipe_json          JSONB       NULL,
    summary              TEXT        NULL,
    tags                 JSONB       NULL,
    summary_version      INT         NULL,
    attempt_count        INT         NOT NULL DEFAULT 0,
    analyzing_started_at TIMESTAMPTZ NULL,
    last_error           TEXT        NULL,
    analysis_seconds     INT         NULL,
    queued_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_video_status ON video (status);

-- 기존 registration 의 영상별 데이터를 video 로 이관 (같은 영상 여러 행이면 DONE 우선 — 확정 결정)
INSERT INTO video (video_id, platform, url, title, thumbnail_url, duration_seconds, status, category,
                    recipe_json, summary, tags, summary_version, attempt_count, analyzing_started_at,
                    last_error, analysis_seconds, queued_at)
SELECT DISTINCT ON (video_id)
        video_id, platform, url, title, thumbnail_url, duration_seconds, status, category,
        recipe_json, summary, tags, summary_version, attempt_count, analyzing_started_at,
        last_error, analysis_seconds, registered_at
    FROM registration
    ORDER BY video_id, (status = 'DONE') DESC, registered_at DESC;

-- registration 을 연결 전용 테이블로 재편 (같은 이름을 재사용하기 위해 새 테이블 생성 후 교체)
CREATE TABLE registration_new (
    user_id       BIGINT      NOT NULL REFERENCES gikka_user (id),
    video_id      TEXT        NOT NULL REFERENCES video (video_id),
    registered_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, video_id)
);
INSERT INTO registration_new (user_id, video_id, registered_at)
    SELECT user_id, video_id, registered_at FROM registration;

DROP TABLE registration;
ALTER TABLE registration_new RENAME TO registration;
