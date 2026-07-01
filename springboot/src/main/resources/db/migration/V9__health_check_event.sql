-- 헬스 체크 실패 이력 (범용 checkKey 기반)
-- 정상(OK)은 저장하지 않고, FAIL로 바뀐 이벤트 + 원인 + 복구 시각만 적립한다.
CREATE TABLE health_check_event (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    check_key        VARCHAR(64)  NOT NULL,
    status           VARCHAR(16)  NOT NULL,          -- DOWN / DEGRADED / RESOLVED
    severity         VARCHAR(16)  NULL,              -- WARN / CRITICAL
    cause            TEXT         NULL,              -- 원인/스택트레이스 요약
    first_failed_at  DATETIME(3)  NULL,
    last_failed_at   DATETIME(3)  NULL,
    resolved_at      DATETIME(3)  NULL,
    source_env       VARCHAR(20)  NOT NULL DEFAULT 'local',
    created_at       DATETIME(3)  NOT NULL,
    updated_at       DATETIME(3)  NULL,
    PRIMARY KEY (id),
    KEY idx_hce_check_key (check_key, last_failed_at),
    KEY idx_hce_status (status, last_failed_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
