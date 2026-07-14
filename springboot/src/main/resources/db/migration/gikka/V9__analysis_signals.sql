-- 분석에 실제로 쓸 수 있었던 원시 신호 목록 (2026-07-14 확정, pattern-raw-signal)
-- 예: ["FRAMES","DESCRIPTION","TRANSCRIPT"] — 값이 빠졌다는 것 자체가 사실(그 신호가 없었음).
-- 경고 문구는 이 목록을 보고 프론트가 도출한다(에러 계약 — 문구는 프론트 소유).
ALTER TABLE video ADD COLUMN analysis_signals jsonb;
