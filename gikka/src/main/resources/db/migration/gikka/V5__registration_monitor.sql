-- [AGENT] 실시간 모니터링(3차 검증단계 확정 — 전 사용자 대기열 추적)을 위해
-- 실패 사유를 화면에서 볼 수 있게 저장한다 (기존엔 로그에만 남고 DB에는 없었음).

ALTER TABLE registration
    ADD COLUMN last_error TEXT NULL;
