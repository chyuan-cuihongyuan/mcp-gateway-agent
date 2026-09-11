-- =============================================================================
-- V0003__channel_body_budget.sql —— 渠道超时与请求大小预算（四期工单 0156，PostgreSQL changelog）
-- 内容：mcp_llm_channel 增 max_body_bytes 列（请求体出站预算；空=不限）。
-- 口径：timeout_ms 列已存在（V0001），渠道不配置=沿用全局默认 60000ms（建库 DEFAULT 口径）；
--       max_body_bytes 为运行期出站前置校验，超限 -32020 拒绝并发布 CHANNEL_BODY_TOO_LARGE 事件。
-- 说明：MySQL 侧增量见 resources/sql/mysql-upgrade-v4-resilience.sql（既有库手工 ALTER）。
-- =============================================================================

ALTER TABLE mcp_llm_channel ADD COLUMN IF NOT EXISTS max_body_bytes BIGINT NULL;
COMMENT ON COLUMN mcp_llm_channel.max_body_bytes IS '渠道请求体预算字节（工单 0156；空=不限，超限 -32020）';
