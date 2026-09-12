-- V0011（工单 0196 AA1）：prompt_version 提示版本表（借鉴 Langfuse prompt management）
CREATE TABLE IF NOT EXISTS mcp_prompt_version (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    prompt_name VARCHAR(128) NOT NULL,
    version     INT          NOT NULL,
    template    TEXT         NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    note        VARCHAR(256),
    operator    VARCHAR(64)  NOT NULL DEFAULT 'unknown',
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_prompt_version UNIQUE (prompt_name, version)
);
COMMENT ON TABLE mcp_prompt_version IS '提示版本表（DRAFT/PUBLISHED/ROLLBACK；同名单一 PUBLISHED 不变式）';
COMMENT ON COLUMN mcp_prompt_version.status IS '状态：DRAFT-草稿，PUBLISHED-已发布，ROLLBACK-已被回滚替代';
CREATE INDEX IF NOT EXISTS idx_prompt_version_name ON mcp_prompt_version (prompt_name, status);
