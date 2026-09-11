-- =============================================================================
-- V0009__channel_context_limit.sql —— 模型上下文长度守卫（四期工单 0162，PostgreSQL changelog）
-- 内容：mcp_llm_channel 增 context_limit_tokens 列（估算 prompt+max_tokens 的上限；空/0=不限制）。
-- 运行口径：TokenEstimator 粗估（CJK 按字/英文按词，字符数兜底可配置），超限 -32023 提前拒绝。
-- 说明：MySQL 侧增量见 resources/sql/mysql-upgrade-v4-resilience.sql。
-- =============================================================================

ALTER TABLE mcp_llm_channel ADD COLUMN IF NOT EXISTS context_limit_tokens INT NULL;
COMMENT ON COLUMN mcp_llm_channel.context_limit_tokens IS '模型上下文上限 token（工单 0162；空/0=不限制，超限 -32023）';
