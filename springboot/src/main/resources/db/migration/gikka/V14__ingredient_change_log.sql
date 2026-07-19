-- 재료 사전 자동 반영 로그 (2026-07-18 확정 — CONTEXT.md "재료 사전 자동 판정" 절).
-- 분석 파이프라인이 사전을 스스로 바꾸게 되면서(수량·단위 변형 병합 + 신규 PENDING AI 판정)
-- 오너의 역할이 "사전 승인"에서 "사후 감사"로 바뀐다 — 이 로그가 그 감사의 원본이다.
-- monitor 재료 사전 화면의 "자동 반영 내역"이 이 테이블을 읽는다. 알림(텔레그램)은 두지 않는다
-- (사용자 확정: 노이즈 — 보고 싶을 때만 본다). 오너 수동 조작은 기록 대상이 아니다(자기가 한
-- 일이라 감사가 필요 없음). 잘못된 자동 반영의 복구는 기존 그룹 해제·재분류로 충분해
-- 로그는 append 전용이다(수정·삭제 API 없음).
CREATE TABLE ingredient_change_log (
    id         BIGSERIAL PRIMARY KEY,
    name       TEXT NOT NULL,           -- 바뀐 재료 이름
    action     TEXT NOT NULL,           -- CLASSIFY(분류) | MERGE(그룹 병합)
    old_value  TEXT NOT NULL,           -- CLASSIFY: 이전 status / MERGE: 이전 match_key
    new_value  TEXT NOT NULL,           -- CLASSIFY: 새 status / MERGE: 새 match_key
    source     TEXT NOT NULL,           -- AUTO_VARIANT(수량·단위 규칙) | AUTO_AUDIT(AI 판정)
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_ingredient_change_log_created ON ingredient_change_log (created_at DESC);
