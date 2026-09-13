-- V0018（工单 0281 AJ5）：model_catalog 模型目录表（借鉴 Ollama registry/OpenRouter 模型元数据）
CREATE TABLE IF NOT EXISTS mcp_model_catalog (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    model            VARCHAR(128) NOT NULL,
    context_limit    INT          NOT NULL DEFAULT 0,
    modalities       VARCHAR(128) NOT NULL DEFAULT 'text',
    pricing_entry_id BIGINT,
    status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    note             VARCHAR(256),
    operator         VARCHAR(64)  NOT NULL DEFAULT 'unknown',
    create_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_model_catalog_model UNIQUE (model)
);
COMMENT ON TABLE mcp_model_catalog IS '模型目录（能力矩阵：context 上限/模态/归属计价条目；与计价表联动校验，缺失警告不阻断）';
COMMENT ON COLUMN mcp_model_catalog.modalities IS '模态集合：text/vision/audio/embedding（逗号拼接）';
