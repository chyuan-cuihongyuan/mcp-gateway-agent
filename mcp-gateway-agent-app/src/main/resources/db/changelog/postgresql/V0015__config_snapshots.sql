-- V0015（工单 0251 AG1）：config_snapshot 配置快照表（借鉴 Nacos 配置快照/Apollo 发布轨迹）
CREATE TABLE IF NOT EXISTS mcp_config_snapshot (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    namespace   VARCHAR(128) NOT NULL,
    config_key  VARCHAR(256) NOT NULL,
    version     INT          NOT NULL,
    content     TEXT         NOT NULL,
    content_md5 VARCHAR(64)  NOT NULL,
    sensitive   BOOLEAN      NOT NULL DEFAULT FALSE,
    publisher   VARCHAR(64)  NOT NULL DEFAULT 'unknown',
    note        VARCHAR(256),
    status      VARCHAR(16)  NOT NULL DEFAULT 'CURRENT',
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_config_snapshot UNIQUE (namespace, config_key, version)
);
COMMENT ON TABLE mcp_config_snapshot IS '配置快照表（CURRENT/SUPERSEDED；同键单一 CURRENT 不变式；敏感项 content 为 enc-v1 密文）';
COMMENT ON COLUMN mcp_config_snapshot.status IS '状态：CURRENT-当前生效，SUPERSEDED-已被后续发布取代';
CREATE INDEX IF NOT EXISTS idx_config_snapshot_ns ON mcp_config_snapshot (namespace, config_key, status);
