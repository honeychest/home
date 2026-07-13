-- [AGENT] 모니터링 확장 (2026-07-13 승인 — 대기열 크기·워커 생존·소요시간·429 이력)
-- analysis_seconds: 마지막 시도의 실제 소요시간(초). DONE/FAILED 전환 시 analyzing_started_at
-- 기준으로 계산해 기록 — 이전엔 진행 중 항목의 "지금 몇 초째"만 보이고 다 끝난 뒤엔 안 남았음.
-- gemini_rate 에 429 발생 이력 추가 — 워커 생존(last_call_at)과 같은 행에 묶어 한 번에 조회.

ALTER TABLE registration
    ADD COLUMN analysis_seconds INT NULL;

-- heartbeat_at: 워커 틱마다(일할 게 없어도) 갱신 — last_call_at 은 429 백오프로 미래로
-- 밀리기도 해서 그것만으론 "스케줄러가 살아있나"를 알 수 없다. 별도 신호로 분리.
ALTER TABLE gemini_rate
    ADD COLUMN rate_limit_count INT NOT NULL DEFAULT 0,
    ADD COLUMN last_rate_limited_at TIMESTAMPTZ NULL,
    ADD COLUMN heartbeat_at TIMESTAMPTZ NULL;
