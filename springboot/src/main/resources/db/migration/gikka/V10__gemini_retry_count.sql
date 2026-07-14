-- 로컬 자체가 불가해 대체 결과가 없는 상태에서 Gemini 가 일시적으로 실패(429·503·타임아웃)한
-- 횟수 (2026-07-14 확정). 모니터링 화면에 "몇 번째 재시도인지" 노출용 — attempt_count 는
-- 이 상황에서 소모되지 않도록 설계돼 있어(claimNext +1 / releaseAfterRateLimit -1 상쇄) 별도 카운터가 필요함.
ALTER TABLE video ADD COLUMN gemini_retry_count integer NOT NULL DEFAULT 0;
