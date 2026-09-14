-- V0019（工单 0337 AP7）：tool_registry 工具注册表（OpenAPI 导入落库，借鉴 langchain 工具抽象/OpenAPI 规范）
CREATE TABLE IF NOT EXISTS mcp_tool_registry (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tool_name         VARCHAR(128) NOT NULL,
    description       VARCHAR(512),
    parameter_schema  TEXT,
    tags              VARCHAR(256),
    source_fingerprint VARCHAR(64),
    status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    operator          VARCHAR(64)  NOT NULL DEFAULT 'unknown',
    create_time       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_tool_registry_name UNIQUE (tool_name)
);
COMMENT ON TABLE mcp_tool_registry IS '工具注册表（AP7：OpenAPI 导入幂等，同指纹跳过；治理台检索数据面）';
COMMENT ON COLUMN mcp_tool_registry.update_time IS '更新时间（应用层维护）';
