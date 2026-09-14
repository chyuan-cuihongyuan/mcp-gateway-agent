-- V0021（工单 0335 AP5）：tool_call_log 工具调用审计表（留痕+配额依据）
CREATE TABLE IF NOT EXISTS mcp_tool_call_log (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    call_id       VARCHAR(64)  NOT NULL,
    tenant_id     VARCHAR(64)  NOT NULL,
    tool_name     VARCHAR(128) NOT NULL,
    param_summary VARCHAR(512),
    status        VARCHAR(16)  NOT NULL,
    cost_ms       BIGINT       NOT NULL DEFAULT 0,
    token_estimate INT         NOT NULL DEFAULT 0,
    at_ms         BIGINT       NOT NULL,
    create_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_tool_call_id UNIQUE (call_id)
);
COMMENT ON TABLE mcp_tool_call_log IS '工具调用审计（AP5：租户×工具滑动窗口限额依据；SUCCESS/TIMEOUT/ERROR/TRUNCATED/QUOTA_REJECTED）';
CREATE INDEX IF NOT EXISTS idx_tool_call_tenant_tool ON mcp_tool_call_log (tenant_id, tool_name, at_ms);
