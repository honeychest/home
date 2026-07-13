-- [AGENT] 영상 설명란(본문) 저장 (2026-07-13 확정 — 재료가 설명란에 원문으로 적힌 경우가 많아
-- 그동안 Gemini 에 아예 전달되지 않던 문제를 해결. 설명란 재료가 있으면 최우선 활용.

ALTER TABLE video
    ADD COLUMN description TEXT NULL;
