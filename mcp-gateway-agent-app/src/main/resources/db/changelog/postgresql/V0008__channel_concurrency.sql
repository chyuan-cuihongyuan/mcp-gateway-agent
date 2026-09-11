-- =============================================================================
-- V0008__channel_concurrency.sql —— 渠道并发上限（四期工单 0161，PostgreSQL changelog）
-- 内容：mcp_llm_channel 增 max_concurrency 列（空/0=不限制兼容存量）。
-- 运行口径：每渠道 Semaphore 排队获取（governance.channel.concurrency-queue-ms，默认 1000ms），
--           超时 -32022 拒绝；finally 保证释放；gauge channel_active_requests{channel}。
-- 说明：MySQL 侧增量见 resources/sql/mysql-upgrade-v4-resilience.sql。
-- =============================================================================

ALTER TABLE mcp_llm_channel ADD COLUMN IF NOT EXISTS max_concurrency INT NULL;
COMMENT ON COLUMN mcp_llm_channel.max_concurrency IS '渠道并发上限（工单 0161；空/0=不限制，超限 -32022）';
