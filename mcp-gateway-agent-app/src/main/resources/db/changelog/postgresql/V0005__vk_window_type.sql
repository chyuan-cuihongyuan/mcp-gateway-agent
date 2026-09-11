-- =============================================================================
-- V0005__vk_window_type.sql —— 滚动窗口配额（四期工单 0158，PostgreSQL changelog）
-- 内容：mcp_virtual_key 增 budget_window_type 列（DAY/WEEK/MONTH 滚动窗口；NULL=旧固定窗口惰性重置）。
-- 运行口径：滑动窗口按时间戳回溯（sumCostSince/countSince/sumTokensSince，created_at >= 起点含边界），
--           硬线拒绝（-32014 段）与软线事件口径不变；未配置=旧行为兼容存量。
-- 说明：MySQL 侧增量见 resources/sql/mysql-upgrade-v4-resilience.sql（既有库手工 ALTER）。
-- =============================================================================

ALTER TABLE mcp_virtual_key ADD COLUMN IF NOT EXISTS budget_window_type VARCHAR(16) NULL;
COMMENT ON COLUMN mcp_virtual_key.budget_window_type IS '预算窗口类型（工单 0158；DAY/WEEK/MONTH 滚动，NULL=旧固定窗口）';
