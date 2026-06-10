ALTER TABLE alert_history
ADD COLUMN source_env VARCHAR(20) NOT NULL DEFAULT 'unknown' AFTER sent_at;

CREATE INDEX idx_alert_source_env_sent_at
ON alert_history (source_env, sent_at);
