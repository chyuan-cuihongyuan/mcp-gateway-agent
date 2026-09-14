-- V0020（工单 0333 AP3）：tool_chain 工具链定义表（步骤 DSL，借鉴 langchain Chain）
CREATE TABLE IF NOT EXISTS mcp_tool_chain (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    chain_name  VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    steps_json  TEXT         NOT NULL,
    tenant_id   VARCHAR(64),
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_tool_chain_name UNIQUE (chain_name)
);
COMMENT ON TABLE mcp_tool_chain IS '工具链定义（AP3：步骤 tool+参数模板 ${step.field} 引用，拓扑执行）';
COMMENT ON COLUMN mcp_tool_chain.update_time IS '更新时间（应用层维护）';
