-- V0017（工单 0265 AH5）：policy_decision_log 策略决策日志表（借鉴 OPA decision logs）
CREATE TABLE IF NOT EXISTS mcp_policy_decision_log (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    at_ms               BIGINT       NOT NULL,
    subject             VARCHAR(256) NOT NULL,
    object              VARCHAR(256) NOT NULL,
    action              VARCHAR(128) NOT NULL,
    decision            VARCHAR(16)  NOT NULL,
    hit_statement_names VARCHAR(512) NOT NULL DEFAULT '',
    cached              BOOLEAN      NOT NULL DEFAULT FALSE,
    cost_ms             BIGINT       NOT NULL DEFAULT 0,
    create_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE mcp_policy_decision_log IS '策略决策日志表（每次评估留痕：输入摘要脱敏 + 命中策略 + 结论 + 缓存标记）';
CREATE INDEX IF NOT EXISTS idx_policy_decision_log_at ON mcp_policy_decision_log (at_ms DESC);
CREATE INDEX IF NOT EXISTS idx_policy_decision_log_decision ON mcp_policy_decision_log (decision);
