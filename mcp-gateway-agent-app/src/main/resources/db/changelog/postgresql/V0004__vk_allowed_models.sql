-- =============================================================================
-- V0004__vk_allowed_models.sql —— API Key 模型白名单（四期工单 0157，PostgreSQL changelog）
-- 内容：mcp_virtual_key 增 allowed_models 列（JSON 数组原文；NULL=不限制兼容存量）。
-- 运行口径：调度前校验（护栏后调度前），白名单外 -32021 拒绝并发布 KEY_MODEL_WHITELIST_DENIED 事件。
-- 说明：MySQL 侧增量见 resources/sql/mysql-upgrade-v4-resilience.sql（既有库手工 ALTER）。
-- =============================================================================

ALTER TABLE mcp_virtual_key ADD COLUMN IF NOT EXISTS allowed_models VARCHAR(1024) NULL;
COMMENT ON COLUMN mcp_virtual_key.allowed_models IS '模型白名单 JSON 数组（工单 0157；NULL=不限制）';
