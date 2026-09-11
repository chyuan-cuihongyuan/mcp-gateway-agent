-- =============================================================================
-- V0002__channel_fallback.sql —— 渠道 fallback 链（四期工单 0155，PostgreSQL changelog）
-- 内容：
--   1) mcp_llm_channel 增 fallback_channel_id 列（自引用；空=无降级）。
--   2) 补账列：num_retries / retry_backoff_ms / retry_on（工单 0105）与
--      balance_probe_url / balance_json_path / balance / balance_time（工单 0108）——
--      mapper 已引用但 V0001 基线遗漏，本脚本一并补齐，保证 PG 新库可执行既有 insert/update。
-- 说明：本脚本经 MigrationRunner 在 schema_version 登记后执行一次；
--       MySQL 侧增量见 resources/sql/mysql-upgrade-v4-resilience.sql（既有库手工 ALTER）。
-- =============================================================================

-- 1) fallback 链列（工单 0155）
ALTER TABLE mcp_llm_channel ADD COLUMN IF NOT EXISTS fallback_channel_id BIGINT NULL;
COMMENT ON COLUMN mcp_llm_channel.fallback_channel_id IS 'fallback 渠道 id（工单 0155；空=无降级，保存时防环校验）';

-- 2) 补账列（0105 重试 / 0108 余额探测）
ALTER TABLE mcp_llm_channel ADD COLUMN IF NOT EXISTS num_retries INT NULL;
ALTER TABLE mcp_llm_channel ADD COLUMN IF NOT EXISTS retry_backoff_ms INT NULL;
ALTER TABLE mcp_llm_channel ADD COLUMN IF NOT EXISTS retry_on VARCHAR(64) NULL;
ALTER TABLE mcp_llm_channel ADD COLUMN IF NOT EXISTS balance_probe_url VARCHAR(512) NULL;
ALTER TABLE mcp_llm_channel ADD COLUMN IF NOT EXISTS balance_json_path VARCHAR(256) NULL;
ALTER TABLE mcp_llm_channel ADD COLUMN IF NOT EXISTS balance VARCHAR(128) NULL;
ALTER TABLE mcp_llm_channel ADD COLUMN IF NOT EXISTS balance_time TIMESTAMP NULL;
